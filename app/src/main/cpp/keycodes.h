// keycodes.h — Linux evdev key codes (input-event-codes.h) mapped to the Android
// KeyEvent name (KeyEvent.keyCodeToString() without the "KEYCODE_" prefix).
// Ported from the original Kotlin LINUX_KEYS table so key names stay stable
// across the rewrite (saved keymaps keep working).
#pragma once

#include <string>
#include <unordered_map>

namespace mapeador {

enum LinuxEventType {
    EV_SYN_T = 0,
    EV_KEY_T = 1,
    EV_REL_T = 2,
};

enum LinuxRelCode {
    REL_X_C = 0,
    REL_Y_C = 1,
    REL_HWHEEL_C = 6,
    REL_WHEEL_C = 8,
    REL_HWHEEL_HI_RES_C = 11,
    REL_WHEEL_HI_RES_C = 12,
};

constexpr int BTN_LEFT_C = 272;
constexpr int BTN_RIGHT_C = 273;
constexpr int BTN_MIDDLE_C = 274;
constexpr int BTN_TASK_C = 279;

inline const std::unordered_map<int, std::string> &linuxKeyNames() {
    static const std::unordered_map<int, std::string> table = [] {
        std::unordered_map<int, std::string> m;
        m[1] = "ESCAPE";
        const char *digits = "1234567890";
        for (int i = 0; i < 10; i++) m[2 + i] = std::string(1, digits[i]);
        m[12] = "MINUS";
        m[13] = "EQUALS";
        m[14] = "DEL";
        m[15] = "TAB";
        const char *row1 = "QWERTYUIOP";
        for (int i = 0; i < 10; i++) m[16 + i] = std::string(1, row1[i]);
        m[26] = "LEFT_BRACKET";
        m[27] = "RIGHT_BRACKET";
        m[28] = "ENTER";
        m[29] = "CTRL_LEFT";
        const char *row2 = "ASDFGHJKL";
        for (int i = 0; i < 9; i++) m[30 + i] = std::string(1, row2[i]);
        m[39] = "SEMICOLON";
        m[40] = "APOSTROPHE";
        m[41] = "GRAVE";
        m[42] = "SHIFT_LEFT";
        m[43] = "BACKSLASH";
        const char *row3 = "ZXCVBNM";
        for (int i = 0; i < 7; i++) m[44 + i] = std::string(1, row3[i]);
        m[51] = "COMMA";
        m[52] = "PERIOD";
        m[53] = "SLASH";
        m[54] = "SHIFT_RIGHT";
        m[55] = "NUMPAD_MULTIPLY";
        m[56] = "ALT_LEFT";
        m[57] = "SPACE";
        m[58] = "CAPS_LOCK";
        for (int i = 0; i <= 9; i++) m[59 + i] = "F" + std::to_string(i + 1);
        m[69] = "NUM_LOCK";
        m[70] = "SCROLL_LOCK";
        m[71] = "NUMPAD_7"; m[72] = "NUMPAD_8"; m[73] = "NUMPAD_9";
        m[74] = "NUMPAD_SUBTRACT";
        m[75] = "NUMPAD_4"; m[76] = "NUMPAD_5"; m[77] = "NUMPAD_6";
        m[78] = "NUMPAD_ADD";
        m[79] = "NUMPAD_1"; m[80] = "NUMPAD_2"; m[81] = "NUMPAD_3"; m[82] = "NUMPAD_0";
        m[83] = "NUMPAD_DOT";
        m[87] = "F11";
        m[88] = "F12";
        m[96] = "NUMPAD_ENTER";
        m[97] = "CTRL_RIGHT";
        m[98] = "NUMPAD_DIVIDE";
        m[100] = "ALT_RIGHT";
        m[102] = "MOVE_HOME";
        m[103] = "DPAD_UP";
        m[104] = "PAGE_UP";
        m[105] = "DPAD_LEFT";
        m[106] = "DPAD_RIGHT";
        m[107] = "MOVE_END";
        m[108] = "DPAD_DOWN";
        m[109] = "PAGE_DOWN";
        m[110] = "INSERT";
        m[111] = "FORWARD_DEL";
        m[125] = "META_LEFT";
        m[126] = "META_RIGHT";
        m[BTN_LEFT_C] = "MOUSE_LEFT";
        m[BTN_RIGHT_C] = "MOUSE_RIGHT";
        m[BTN_MIDDLE_C] = "MOUSE_MIDDLE";
        m[275] = "BACK";
        m[276] = "FORWARD";
        return m;
    }();
    return table;
}

inline std::string linuxKeyName(int code) {
    const auto &table = linuxKeyNames();
    auto it = table.find(code);
    return it == table.end() ? std::string() : it->second;
}

} // namespace mapeador
