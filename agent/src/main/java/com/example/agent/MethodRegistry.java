package com.example.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class MethodRegistry {
    private final List<MethodInfo> METHODS = new ArrayList<>();

    public void register(MethodInfo info) {
        METHODS.add(info);
    }

    public List<String> getMethodSignatures() {
        return METHODS.stream()
            .map(MethodInfo::toString)
            .collect(Collectors.toList());
    }

    public Map<String, List<ParamInfo>> getMethodsWithParam() {
        Map<String, List<ParamInfo>> result = new HashMap<>();
        for (MethodInfo methodInfo : METHODS) {
            result.put(methodInfo.toStringWithParam(), methodInfo.params);
        }
        return result;
    }

    public Optional<List<ParamInfo>> getParamsForMethod(String fullSignature) {
        return METHODS.stream()
            .filter(m -> m.toStringWithParam().equals(fullSignature))
            .map(m -> m.params)
            .findFirst();
    }

    // STATIC CLASSES

    public static class MethodInfo {
        public final String className;
        public final String methodName;
        public final List<ParamInfo> params;

        public MethodInfo(String className, String methodName, List<ParamInfo> params) {
            this.className = className;
            this.methodName = methodName;
            this.params = params;
        }

        @Override
        public String toString() {
            return className + "." + methodName;
        }

        public String toStringWithParam() {
            return className + "." + methodName + "(" +
                params.stream().map(ParamInfo::toString).collect(Collectors.joining(", ")) +
                ")";
        }
    }

    public static class ParamInfo {
        public final String typeName;
        public final List<FieldInfo> fields;

        public ParamInfo(String typeName, List<FieldInfo> fields) {
            this.typeName = typeName;
            this.fields = fields;
        }

        @Override
        public String toString() {
            if (fields.isEmpty()) return typeName;
            return typeName + " { " +
                fields.stream().map(FieldInfo::toString).collect(Collectors.joining("; ")) +
                " }";
        }
    }

    public static class FieldInfo {
        public final String type;
        public final String name;

        public FieldInfo(String type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public String toString() {
            return type + " " + name;
        }
    }
}
