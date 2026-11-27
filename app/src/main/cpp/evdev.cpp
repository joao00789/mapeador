#include "evdev.h"

#include <linux/input.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <poll.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <cstring>
#include <algorithm>

namespace mapeador {

namespace {

bool hasBit(const unsigned long *bits, unsigned int bit) {
    const unsigned int bitsPerWord = (unsigned int) (sizeof(unsigned long) * 8);
    return (bits[bit / bitsPerWord] & (1UL << (bit % bitsPerWord))) != 0;
}

} // namespace

void evdevProbeCapabilities(int fd, DeviceInfo &info) {
    unsigned long eventBits[8] = {0};
    if (ioctl(fd, EVIOCGBIT(0, sizeof(eventBits)), eventBits) < 0) return;
    const bool hasRelative = hasBit(eventBits, EV_REL);
    const bool hasKeys = hasBit(eventBits, EV_KEY);

    if (hasRelative) {
        unsigned long relBits[8] = {0};
        if (ioctl(fd, EVIOCGBIT(EV_REL, sizeof(relBits)), relBits) >= 0) {
            info.hasRelativeMotion = hasBit(relBits, REL_X) && hasBit(relBits, REL_Y);
        }
    }
    if (hasKeys) {
        unsigned long keyBits[32] = {0};
        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keyBits)), keyBits) >= 0) {
            info.hasMouseButtons = hasBit(keyBits, BTN_LEFT) || hasBit(keyBits, BTN_RIGHT) ||
                hasBit(keyBits, BTN_MIDDLE) || hasBit(keyBits, BTN_SIDE) || hasBit(keyBits, BTN_EXTRA);
            info.hasKeyboardKeys = hasBit(keyBits, KEY_A) || hasBit(keyBits, KEY_ENTER) ||
                hasBit(keyBits, KEY_SPACE);
        }
    }
}

std::string evdevIdentity(int fd) {
    char phys[256] = {0};
    if (ioctl(fd, EVIOCGPHYS(sizeof(phys)), phys) >= 0 && phys[0] != '\0') return std::string(phys);
    char uniq[256] = {0};
    if (ioctl(fd, EVIOCGUNIQ(sizeof(uniq)), uniq) >= 0 && uniq[0] != '\0') return std::string(uniq);
    return std::string();
}

EvdevManager::~EvdevManager() {
    stop();
    for (auto &d : devices_) {
        if (d.fd >= 0) {
            if (d.grabbed) ioctl(d.fd, EVIOCGRAB, 0);
            close(d.fd);
        }
    }
}

void EvdevManager::rescan() {
    DIR *dir = opendir("/dev/input");
    if (!dir) return;
    std::vector<std::string> paths;
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
        std::string name = entry->d_name;
        if (name.rfind("event", 0) == 0) paths.push_back("/dev/input/" + name);
    }
    closedir(dir);
    std::sort(paths.begin(), paths.end());

    for (const auto &path : paths) {
        bool already = std::any_of(devices_.begin(), devices_.end(),
            [&](const DeviceInfo &d) { return d.path == path; });
        if (already) continue;

        int fd = open(path.c_str(), O_RDWR | O_NONBLOCK);
        if (fd < 0) fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        DeviceInfo info;
        info.path = path;
        info.fd = fd;
        evdevProbeCapabilities(fd, info);
        info.identity = evdevIdentity(fd);

        if (!info.hasRelativeMotion && !info.hasMouseButtons && !info.hasKeyboardKeys) {
            close(fd);
            continue;
        }
        devices_.push_back(info);
    }
}

int EvdevManager::setMouseGrabbed(bool grabbed) {
    int count = 0;
    for (auto &d : devices_) {
        if (!d.hasRelativeMotion && !d.hasMouseButtons) continue;
        if (ioctl(d.fd, EVIOCGRAB, grabbed ? 1 : 0) == 0) {
            d.grabbed = grabbed;
            count++;
        }
    }
    return count;
}

int EvdevManager::setKeyboardGrabbed(bool grabbed) {
    int count = 0;
    for (auto &d : devices_) {
        if (!d.hasKeyboardKeys || d.hasRelativeMotion || d.hasMouseButtons) {
            if (!d.hasKeyboardKeys) continue;
        }
        if (ioctl(d.fd, EVIOCGRAB, grabbed ? 1 : 0) == 0) {
            d.grabbed = grabbed;
            count++;
        }
    }
    return count;
}

std::vector<DeviceInfo> EvdevManager::snapshotDevices() { return devices_; }
int EvdevManager::deviceCount() { return (int) devices_.size(); }

void EvdevManager::startReading(EventCallback callback, LogCallback log) {
    if (running_) return;
    callback_ = std::move(callback);
    log_ = std::move(log);
    if (pipe(wakeupPipe_) != 0) { wakeupPipe_[0] = wakeupPipe_[1] = -1; }
    running_ = true;
    readerThread_ = std::thread(&EvdevManager::readLoop, this);
}

void EvdevManager::stop() {
    if (!running_) return;
    running_ = false;
    if (wakeupPipe_[1] >= 0) {
        char byte = 0;
        ssize_t written = write(wakeupPipe_[1], &byte, 1);
        (void) written;
    }
    if (readerThread_.joinable()) readerThread_.join();
    if (wakeupPipe_[0] >= 0) close(wakeupPipe_[0]);
    if (wakeupPipe_[1] >= 0) close(wakeupPipe_[1]);
    wakeupPipe_[0] = wakeupPipe_[1] = -1;
}

void EvdevManager::readLoop() {
    if (log_) log_("Loop de leitura evdev iniciado com " + std::to_string(devices_.size()) + " dispositivo(s)");
    std::vector<struct pollfd> fds;
    while (running_) {
        fds.clear();
        for (auto &d : devices_) {
            struct pollfd pfd{};
            pfd.fd = d.fd;
            pfd.events = POLLIN;
            fds.push_back(pfd);
        }
        if (wakeupPipe_[0] >= 0) {
            struct pollfd wake{};
            wake.fd = wakeupPipe_[0];
            wake.events = POLLIN;
            fds.push_back(wake);
        }
        if (fds.empty()) {
            // No devices yet (e.g. USB not plugged in): wait and let the caller rescan().
            usleep(300 * 1000);
            continue;
        }

        int result = poll(fds.data(), fds.size(), 1000);
        if (result <= 0) continue;

        for (size_t i = 0; i < devices_.size(); i++) {
            if (!(fds[i].revents & POLLIN)) continue;
            struct input_event ev{};
            ssize_t bytesRead;
            while ((bytesRead = read(devices_[i].fd, &ev, sizeof(ev))) == (ssize_t) sizeof(ev)) {
                RawEvent raw;
                raw.devicePath = devices_[i].path;
                raw.type = ev.type;
                raw.code = ev.code;
                raw.value = ev.value;
                if (callback_) callback_(raw);
            }
        }
    }
    if (log_) log_("Loop de leitura evdev encerrado");
}

} // namespace mapeador
