// json.hpp — minimal single-header JSON reader/writer used by the native engine.
// Not a general-purpose JSON library: just enough to parse/produce the keymap
// documents this app uses (objects, arrays, strings, numbers, bools, null).
#pragma once

#include <string>
#include <vector>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <cctype>
#include <cstdlib>

namespace json {

enum class Type { Null, Bool, Number, String, Array, Object };

class Value {
public:
    Type type = Type::Null;
    bool boolValue = false;
    double numberValue = 0.0;
    std::string stringValue;
    std::vector<Value> arrayValue;
    std::map<std::string, Value> objectValue;

    Value() = default;
    static Value makeObject() { Value v; v.type = Type::Object; return v; }
    static Value makeArray() { Value v; v.type = Type::Array; return v; }
    static Value fromBool(bool b) { Value v; v.type = Type::Bool; v.boolValue = b; return v; }
    static Value fromNumber(double n) { Value v; v.type = Type::Number; v.numberValue = n; return v; }
    static Value fromString(const std::string &s) { Value v; v.type = Type::String; v.stringValue = s; return v; }

    bool isObject() const { return type == Type::Object; }
    bool isArray() const { return type == Type::Array; }
    bool isNull() const { return type == Type::Null; }

    // Object field access with defaults; never throws.
    const Value *find(const std::string &key) const {
        if (type != Type::Object) return nullptr;
        auto it = objectValue.find(key);
        if (it == objectValue.end()) return nullptr;
        return &it->second;
    }

    std::string getString(const std::string &key, const std::string &fallback = "") const {
        const Value *v = find(key);
        if (!v || v->type != Type::String) return fallback;
        return v->stringValue;
    }

    double getNumber(const std::string &key, double fallback = 0.0) const {
        const Value *v = find(key);
        if (!v || v->type != Type::Number) return fallback;
        return v->numberValue;
    }

    bool getBool(const std::string &key, bool fallback = false) const {
        const Value *v = find(key);
        if (!v || v->type != Type::Bool) return fallback;
        return v->boolValue;
    }

    void set(const std::string &key, const Value &v) {
        type = Type::Object;
        objectValue[key] = v;
    }

    void push(const Value &v) {
        type = Type::Array;
        arrayValue.push_back(v);
    }
};

namespace detail {

class Parser {
public:
    explicit Parser(const std::string &text) : s(text), pos(0), len(text.size()) {}

    Value parse() {
        skipWs();
        Value v = parseValue();
        return v;
    }

private:
    const std::string &s;
    size_t pos;
    size_t len;

    char peek() const { return pos < len ? s[pos] : '\0'; }
    char next() { return pos < len ? s[pos++] : '\0'; }
    void skipWs() {
        while (pos < len) {
            char c = s[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    Value parseValue() {
        skipWs();
        char c = peek();
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBool();
        if (c == 'n') { pos += 4; return Value(); }
        return parseNumber();
    }

    Value parseObject() {
        Value v = Value::makeObject();
        next(); // {
        skipWs();
        if (peek() == '}') { next(); return v; }
        while (true) {
            skipWs();
            Value key = parseString();
            skipWs();
            if (peek() == ':') next();
            Value val = parseValue();
            v.objectValue[key.stringValue] = val;
            skipWs();
            char c = next();
            if (c == '}') break;
            if (c != ',') break;
        }
        return v;
    }

    Value parseArray() {
        Value v = Value::makeArray();
        next(); // [
        skipWs();
        if (peek() == ']') { next(); return v; }
        while (true) {
            Value val = parseValue();
            v.arrayValue.push_back(val);
            skipWs();
            char c = next();
            if (c == ']') break;
            if (c != ',') break;
        }
        return v;
    }

    Value parseString() {
        Value v;
        v.type = Type::String;
        if (peek() != '"') return v;
        next(); // opening quote
        std::string out;
        while (pos < len) {
            char c = next();
            if (c == '"') break;
            if (c == '\\' && pos < len) {
                char esc = next();
                switch (esc) {
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'u': {
                        // Minimal \uXXXX support: only handles the BMP ASCII range we need.
                        if (pos + 4 <= len) {
                            std::string hex = s.substr(pos, 4);
                            pos += 4;
                            long code = strtol(hex.c_str(), nullptr, 16);
                            if (code < 128) out += static_cast<char>(code);
                        }
                        break;
                    }
                    default: out += esc; break;
                }
            } else {
                out += c;
            }
        }
        v.stringValue = out;
        return v;
    }

    Value parseBool() {
        Value v;
        v.type = Type::Bool;
        if (s.compare(pos, 4, "true") == 0) { v.boolValue = true; pos += 4; }
        else if (s.compare(pos, 5, "false") == 0) { v.boolValue = false; pos += 5; }
        return v;
    }

    Value parseNumber() {
        size_t start = pos;
        if (peek() == '-' || peek() == '+') next();
        while (pos < len && (isdigit((unsigned char) peek()) || peek() == '.' ||
                             peek() == 'e' || peek() == 'E' || peek() == '-' || peek() == '+')) {
            next();
        }
        Value v;
        v.type = Type::Number;
        std::string num = s.substr(start, pos - start);
        v.numberValue = num.empty() ? 0.0 : strtod(num.c_str(), nullptr);
        return v;
    }
};

inline void escapeInto(std::ostringstream &out, const std::string &s) {
    out << '"';
    for (char c : s) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default: out << c; break;
        }
    }
    out << '"';
}

inline void writeInto(std::ostringstream &out, const Value &v) {
    switch (v.type) {
        case Type::Null: out << "null"; break;
        case Type::Bool: out << (v.boolValue ? "true" : "false"); break;
        case Type::Number: {
            double d = v.numberValue;
            if (d == static_cast<long long>(d)) out << static_cast<long long>(d);
            else out << d;
            break;
        }
        case Type::String: escapeInto(out, v.stringValue); break;
        case Type::Array: {
            out << '[';
            for (size_t i = 0; i < v.arrayValue.size(); i++) {
                if (i) out << ',';
                writeInto(out, v.arrayValue[i]);
            }
            out << ']';
            break;
        }
        case Type::Object: {
            out << '{';
            bool first = true;
            for (const auto &kv : v.objectValue) {
                if (!first) out << ',';
                first = false;
                escapeInto(out, kv.first);
                out << ':';
                writeInto(out, kv.second);
            }
            out << '}';
            break;
        }
    }
}

} // namespace detail

inline Value parse(const std::string &text) {
    detail::Parser parser(text);
    return parser.parse();
}

inline std::string dump(const Value &v) {
    std::ostringstream out;
    detail::writeInto(out, v);
    return out.str();
}

} // namespace json
