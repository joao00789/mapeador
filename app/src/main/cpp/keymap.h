// keymap.h — the keymap document: the set of virtual controls (buttons,
// joystick, mouse-look area, etc.) the editor saves and the engine executes.
#pragma once

#include <string>
#include <vector>
#include "json.hpp"

namespace mapeador {

enum class ControlType {
    Touch,        // tap-and-hold a point on screen while the key is held
    Swipe,        // short horizontal swipe on key press
    MousePick,    // behaves like Touch but bound to a mouse button
    Joystick,     // WASD/arrow virtual joystick
    MouseLook,    // "Mouse FPS": relative mouse deltas -> camera drag (aim) or free cursor
    MouseDrag,    // holds a touch at a fixed point and drags it by the mouse deltas
    Unknown,
};

ControlType controlTypeFromString(const std::string &s);
std::string controlTypeToString(ControlType type);

struct Control {
    std::string id;
    ControlType type = ControlType::Unknown;
    std::string key;          // Android key name (e.g. "SPACE", "MOUSE_LEFT") bound to this control
    float x = 0.5f;            // fraction of screen width, 0..1
    float y = 0.5f;            // fraction of screen height, 0..1
    float radius = 0.85f;       // relative size of the on-screen indicator
    float sizeScale = 1.0f;
    bool stopOnOpposite = true; // joystick: releasing WASD's opposite key cancels movement on that axis
    std::string mousePosition = "Centro"; // mouse-look: where the aim/camera touch area is anchored
    float cursorX = 0.5f;
    float cursorY = 0.5f;
    bool preventViewFreeze = true;
    float sensitivityX = 50.0f; // 0..100
    float sensitivityY = 50.0f; // 0..100
    int smoothing = 35;        // 0..100
    float cursorSpeed = 1.6f;
    float cursorSize = 1.0f;
    std::string pressMode = "HOLD"; // HOLD or TOGGLE
    int responseMs = 0;         // artificial delay before the touch is released, 0..300

    json::Value toJson() const;
    static Control fromJson(const json::Value &item);
};

struct Keymap {
    std::vector<Control> controls;
    std::string emergencyKey = "F12";
    bool captureKeyboard = false;
    int initialMouseMode = 0; // 0 = CURSOR, 1 = MIRA (aim)

    std::string toJsonString() const;
    static Keymap fromJsonString(const std::string &text);
};

} // namespace mapeador
