#include "keymap.h"

namespace mapeador {

ControlType controlTypeFromString(const std::string &s) {
    if (s == "TOUCH") return ControlType::Touch;
    if (s == "SWIPE") return ControlType::Swipe;
    if (s == "MOUSE_PICK") return ControlType::MousePick;
    if (s == "JOYSTICK") return ControlType::Joystick;
    if (s == "MOUSE_LOOK") return ControlType::MouseLook;
    if (s == "MOUSE_DRAG") return ControlType::MouseDrag;
    return ControlType::Unknown;
}

std::string controlTypeToString(ControlType type) {
    switch (type) {
        case ControlType::Touch: return "TOUCH";
        case ControlType::Swipe: return "SWIPE";
        case ControlType::MousePick: return "MOUSE_PICK";
        case ControlType::Joystick: return "JOYSTICK";
        case ControlType::MouseLook: return "MOUSE_LOOK";
        case ControlType::MouseDrag: return "MOUSE_DRAG";
        default: return "UNKNOWN";
    }
}

json::Value Control::toJson() const {
    json::Value v = json::Value::makeObject();
    v.set("id", json::Value::fromString(id));
    v.set("type", json::Value::fromString(controlTypeToString(type)));
    v.set("key", json::Value::fromString(key));
    v.set("x", json::Value::fromNumber(x));
    v.set("y", json::Value::fromNumber(y));
    v.set("radius", json::Value::fromNumber(radius));
    v.set("sizeScale", json::Value::fromNumber(sizeScale));
    v.set("stopOnOpposite", json::Value::fromBool(stopOnOpposite));
    v.set("mousePosition", json::Value::fromString(mousePosition));
    v.set("cursorX", json::Value::fromNumber(cursorX));
    v.set("cursorY", json::Value::fromNumber(cursorY));
    v.set("preventViewFreeze", json::Value::fromBool(preventViewFreeze));
    v.set("sensitivityX", json::Value::fromNumber(sensitivityX));
    v.set("sensitivityY", json::Value::fromNumber(sensitivityY));
    v.set("smoothing", json::Value::fromNumber(smoothing));
    v.set("cursorSpeed", json::Value::fromNumber(cursorSpeed));
    v.set("cursorSize", json::Value::fromNumber(cursorSize));
    v.set("pressMode", json::Value::fromString(pressMode));
    v.set("responseMs", json::Value::fromNumber(responseMs));
    return v;
}

namespace {
float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}

Control Control::fromJson(const json::Value &item) {
    Control c;
    c.id = item.getString("id");
    c.type = controlTypeFromString(item.getString("type"));
    c.key = item.getString("key", "Tecla?");
    c.x = clampf((float) item.getNumber("x", 0.5), 0.0f, 1.0f);
    c.y = clampf((float) item.getNumber("y", 0.5), 0.0f, 1.0f);
    c.radius = clampf((float) item.getNumber("radius", 0.85), 0.2f, 1.5f);
    c.sizeScale = clampf((float) item.getNumber("sizeScale", 1.0), 0.5f, 2.0f);
    c.stopOnOpposite = item.getBool("stopOnOpposite", true);
    c.mousePosition = item.getString("mousePosition", "Centro");
    c.cursorX = clampf((float) item.getNumber("cursorX", 0.5), 0.0f, 1.0f);
    c.cursorY = clampf((float) item.getNumber("cursorY", 0.5), 0.0f, 1.0f);
    c.preventViewFreeze = item.getBool("preventViewFreeze", true);
    c.sensitivityX = clampf((float) item.getNumber("sensitivityX", 80.0), 0.0f, 100.0f);
    c.sensitivityY = clampf((float) item.getNumber("sensitivityY", 80.0), 0.0f, 100.0f);
    c.smoothing = (int) clampf((float) item.getNumber("smoothing", 0), 0.0f, 100.0f);
    c.cursorSpeed = clampf((float) item.getNumber("cursorSpeed", 3.2), 0.0f, 4.0f);
    c.cursorSize = clampf((float) item.getNumber("cursorSize", 1.0), 0.6f, 1.8f);
    c.pressMode = item.getString("pressMode", "HOLD");
    c.responseMs = (int) clampf((float) item.getNumber("responseMs", 0), 0.0f, 300.0f);
    return c;
}

std::string Keymap::toJsonString() const {
    json::Value root = json::Value::makeObject();
    json::Value array = json::Value::makeArray();
    for (const auto &c : controls) array.push(c.toJson());
    root.set("controls", array);
    root.set("emergencyKey", json::Value::fromString(emergencyKey));
    root.set("captureKeyboard", json::Value::fromBool(captureKeyboard));
    root.set("initialMouseMode", json::Value::fromNumber(initialMouseMode));
    return json::dump(root);
}

Keymap Keymap::fromJsonString(const std::string &text) {
    Keymap keymap;
    if (text.empty()) return keymap;
    json::Value root = json::parse(text);

    // Accept either {"controls": [...], ...} or a bare top-level array, so the
    // engine can also load the array-only format the previous app version used.
    const json::Value *array = &root;
    if (root.isObject()) {
        keymap.emergencyKey = root.getString("emergencyKey", "F12");
        keymap.captureKeyboard = root.getBool("captureKeyboard", false);
        keymap.initialMouseMode = (int) root.getNumber("initialMouseMode", 0);
        const json::Value *found = root.find("controls");
        if (found && found->isArray()) array = found;
        else return keymap;
    } else if (!root.isArray()) {
        return keymap;
    }

    for (const auto &item : array->arrayValue) {
        Control c = Control::fromJson(item);
        if (c.type != ControlType::Unknown) keymap.controls.push_back(c);
    }
    return keymap;
}

} // namespace mapeador
