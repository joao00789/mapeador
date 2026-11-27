// evdev.h — enumerates and reads /dev/input/event* nodes directly. The engine
// runs as the Shizuku (shell) identity, so it can open these nodes itself
// without any JNI round-trip for file descriptors.
#pragma once

#include <string>
#include <vector>
#include <functional>
#include <atomic>
#include <thread>

namespace mapeador {

struct RawEvent {
    std::string devicePath;
    int type = 0;   // EV_KEY, EV_REL, EV_SYN, ...
    int code = 0;
    int value = 0;
};

struct DeviceInfo {
    std::string path;
    int fd = -1;
    bool hasRelativeMotion = false; // REL_X + REL_Y present -> this is the mouse-motion node
    bool hasMouseButtons = false;   // BTN_LEFT/RIGHT/MIDDLE/SIDE/EXTRA present
    bool hasKeyboardKeys = false;   // KEY_A/ENTER/SPACE present
    bool grabbed = false;
    std::string identity; // device group id (EVIOCGPHYS/EVIOCGUNIQ), used to merge motion+button nodes
};

// Probes a single already-open fd's capabilities (EVIOCGBIT). Exposed for testing.
void evdevProbeCapabilities(int fd, DeviceInfo &info);
std::string evdevIdentity(int fd);

class EvdevManager {
public:
    using EventCallback = std::function<void(const RawEvent &)>;
    using LogCallback = std::function<void(const std::string &)>;

    ~EvdevManager();

    // Re-scans /dev/input/event*, opening newly plugged devices. Safe to call
    // periodically; already-open devices are left untouched.
    void rescan();

    // Grabs (EVIOCGRAB=1) or releases (EVIOCGRAB=0) exclusive access to the
    // mouse-motion node and any node grouped with it by identity. Returns the
    // number of nodes successfully grabbed.
    int setMouseGrabbed(bool grabbed);
    int setKeyboardGrabbed(bool grabbed);

    // Starts a background thread that poll()s every open fd and invokes
    // `callback` for every event, including EV_SYN/SYN_REPORT markers.
    void startReading(EventCallback callback, LogCallback log);
    void stop();

    std::vector<DeviceInfo> snapshotDevices();
    int deviceCount();

private:
    std::vector<DeviceInfo> devices_;
    std::atomic<bool> running_{false};
    std::thread readerThread_;
    EventCallback callback_;
    LogCallback log_;
    int wakeupPipe_[2] = {-1, -1};

    void readLoop();
};

} // namespace mapeador
