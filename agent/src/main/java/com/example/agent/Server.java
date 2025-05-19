package com.example.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class Server {
    private static volatile Server instance;
    private static Injector injector;
    private final MethodRegistry methodRegistry;
    private final ObjectMapper mapper;
    private Server(MethodRegistry methodRegistry, ObjectMapper mapper) {
        this.methodRegistry = methodRegistry;
        this.mapper = mapper;
        startHttpServer();
    }
    public static Server getInstance(MethodRegistry methodRegistry, ObjectMapper mapper) {
        Server result = instance;
        if (result != null) {
            return result;
        }
        synchronized (Server.class) {
            result = instance;
            if (result == null) {
                instance = result = new Server(methodRegistry, mapper);
            }
            return result;
        }
    }

    public void setInjector(Injector injectorInstance) {
        injector = injectorInstance;
    }

    private void startHttpServer() {
        int port = 8080;
        HttpServer server = null;

        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (IOException e) {
                System.out.println("Port " + port + " occupied, trying next port...");
                port++;
            }
        }

        registerRoute(server);
        server.setExecutor(null);
        server.start();

        System.out.println("HTTP Server started on port " + port);
    }

    private void registerRoute(HttpServer server) {
        // Register route
        server.createContext("/ping", exchange -> {
            byte[] bytes = "pong\n".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/echo", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Read request body
                InputStream is = exchange.getRequestBody();
                String body = new BufferedReader(new InputStreamReader(is))
                    .lines()
                    .collect(Collectors.joining("\n"));

                System.out.println("Received POST body: " + body);

                byte[] bytes = body.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            } else {
                // Method not allowed
                exchange.sendResponseHeaders(405, -1); // 405 Method Not Allowed
                exchange.close();
            }
        });

        server.createContext("/list", exchange -> {
            List<String> methods = methodRegistry.getMethodSignatures();
            byte[] bytes = methods.toString().getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/list-methods", exchange -> {
            Map<String, List<MethodRegistry.ParamInfo>> methods = methodRegistry.getMethodsWithParam();
            ObjectMapper mapper = new ObjectMapper();
            byte[] bytes = mapper.writeValueAsBytes(methods.keySet());
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/get-method-params", exchange -> {
            try {
                // Check if it's a GET request
                if (!"GET".equals(exchange.getRequestMethod())) {
                    String errorMessage = "Method not allowed. Use GET request.";
                    byte[] errorBytes = errorMessage.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(405, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    return;
                }

                // Parse the query string to get the method key
                String query = exchange.getRequestURI().getQuery();
                String methodKey = null;

                if (query != null && query.startsWith("method=")) {
                    methodKey = URLDecoder.decode(query.substring(7),
                        StandardCharsets.UTF_8.toString());
                }

                if (methodKey == null || methodKey.isEmpty()) {
                    String errorMessage = "Missing required 'method' parameter";
                    byte[] errorBytes = errorMessage.getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(400, errorBytes.length);
                    exchange.getResponseBody().write(errorBytes);
                    return;
                }

                // Get params for the specific method
                Optional<List<MethodRegistry.ParamInfo>> paramsOpt = methodRegistry.getParamsForMethod(methodKey);

                ObjectMapper mapper = new ObjectMapper();
                if (paramsOpt.isPresent()) {
                    byte[] bytes = mapper.writeValueAsBytes(paramsOpt.get());
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } else {
                    // Method not found
                    String errorMessage = "Method not found: " + methodKey;
                    sendErrorResponse(exchange, 500, errorMessage);
                }
            } catch (Exception e) {
                String errorMessage = "Error processing request: " + e.getMessage();
                sendErrorResponse(exchange, 500, errorMessage);
            } finally {
                exchange.close();
            }
        });

        server.createContext("/invoke-method", this::handleInvoke);
    }

    private Object createNestedClassInstance(Class<?> nestedClass, JsonNode jsonNode) throws Exception {
        // For the Request class specifically with an int value
        if (nestedClass.getSimpleName().equals("Request") && jsonNode.has("value") && jsonNode.get("value").isInt()) {
            // Use the constructor that takes an int parameter
            Constructor<?> constructor = nestedClass.getConstructor(int.class);
            return constructor.newInstance(jsonNode.get("value").asInt());
        }

        // More general approach - collect all field values
        Map<String, Object> fieldValues = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode fieldValue = entry.getValue();

            if (fieldValue.isInt()) {
                fieldValues.put(fieldName, fieldValue.asInt());
            } else if (fieldValue.isLong()) {
                fieldValues.put(fieldName, fieldValue.asLong());
            } else if (fieldValue.isDouble()) {
                fieldValues.put(fieldName, fieldValue.asDouble());
            } else if (fieldValue.isBoolean()) {
                fieldValues.put(fieldName, fieldValue.asBoolean());
            } else if (fieldValue.isTextual()) {
                fieldValues.put(fieldName, fieldValue.asText());
            } else {
                fieldValues.put(fieldName, null); // Placeholder for complex objects
            }
        }

        // Try to find a suitable constructor
        Constructor<?>[] constructors = nestedClass.getConstructors();

        // Try each constructor
        for (Constructor<?> constructor : constructors) {
            Class<?>[] paramTypes = constructor.getParameterTypes();

            // Special handling for no-arg constructor
            if (paramTypes.length == 0) {
                Object instance = constructor.newInstance();
                // Set fields via reflection
                for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
                    try {
                        Field field = nestedClass.getDeclaredField(entry.getKey());
                        field.setAccessible(true);
                        field.set(instance, entry.getValue());
                    } catch (NoSuchFieldException e) {
                        // Ignore fields that don't exist
                    }
                }
                return instance;
            }

            // For single parameter constructor, check if it matches one of our field types
            if (paramTypes.length == 1) {
                for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
                    if (entry.getValue() != null && paramTypes[0].isAssignableFrom(entry.getValue().getClass())) {
                        try {
                            return constructor.newInstance(entry.getValue());
                        } catch (Exception e) {
                            // Try another constructor
                        }
                    }
                }
            }
        }

        throw new IllegalArgumentException("Could not find suitable constructor for class: " + nestedClass.getName());
    }

    private Object deserializeInnerClass(Class<?> innerClass, JsonNode jsonNode, Object outerInstance) throws Exception {
        // Find a suitable constructor for the inner class
        Constructor<?>[] constructors = innerClass.getDeclaredConstructors();
        Constructor<?> innerConstructor = null;

        // First look for a constructor that takes just the outer instance
        for (Constructor<?> constructor : constructors) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 1 &&
                paramTypes[0].isAssignableFrom(outerInstance.getClass())) {
                innerConstructor = constructor;
                break;
            }
        }

        if (innerConstructor != null) {
            // Create the inner class instance
            innerConstructor.setAccessible(true);
            Object innerInstance = innerConstructor.newInstance(outerInstance);

            // Set fields from JSON
            Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();

                try {
                    Field classField = innerClass.getDeclaredField(fieldName);
                    classField.setAccessible(true);

                    if (fieldValue.isInt()) {
                        classField.set(innerInstance, fieldValue.asInt());
                    } else if (fieldValue.isLong()) {
                        classField.set(innerInstance, fieldValue.asLong());
                    } else if (fieldValue.isDouble()) {
                        classField.set(innerInstance, fieldValue.asDouble());
                    } else if (fieldValue.isBoolean()) {
                        classField.set(innerInstance, fieldValue.asBoolean());
                    } else if (fieldValue.isTextual()) {
                        classField.set(innerInstance, fieldValue.asText());
                    } else {
                        // For complex objects, could recursively deserialize
                        // For simplicity, we're only handling primitive types here
                        throw new IllegalArgumentException("Unsupported field type for field: " + fieldName);
                    }
                } catch (NoSuchFieldException e) {
                    // Skip fields that don't exist
                }
            }

            return innerInstance;
        } else {
            throw new IllegalArgumentException("Could not find suitable constructor for inner class: " + innerClass.getName());
        }
    }

    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] responseBytes = message.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    public void handleInvoke(HttpExchange exchange) {
        try {
            if (!isPost(exchange)) {
                sendError(exchange, 405, "Method not allowed");
            }

            JsonNode req = parseJson(exchange);
            if (req == null) return;

            if (!hasRequiredFields(req)) {
                sendError(exchange, 400, "Missing method or params");
            }

            String signature = req.get("method").asText();

            List<MethodRegistry.ParamInfo> paramInfo = getParamInfo(signature, exchange);
            if (paramInfo == null) return;

            MethodMeta meta = parseSignature(signature, exchange);
            if (meta == null) return;

            Object instance = createInstance(meta.className, exchange);
            if (instance == null) return;

            Object[] args = buildArgs(paramInfo, req.get("params"), exchange);
            if (args == null) return;

            Object result = invoke(instance, meta.methodName, args, paramInfo, exchange);
            if (exchange.getResponseCode() != 200) return;

            sendJson(exchange, result);
        } finally {
            exchange.close();
        }
    }

    private static class MethodMeta {
        final String className;
        final String methodName;

        MethodMeta(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }

    private boolean isPost(HttpExchange exchange) {
        return "POST".equals(exchange.getRequestMethod());
    }

    private JsonNode parseJson(HttpExchange exchange) {
        try {
            return mapper.readTree(exchange.getRequestBody());
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    private boolean hasRequiredFields(JsonNode req) {
        return req.has("method") && req.has("params");
    }

    private List<MethodRegistry.ParamInfo> getParamInfo(String signature, HttpExchange exchange) {
        Optional<List<MethodRegistry.ParamInfo>> info = methodRegistry.getParamsForMethod(signature);
        if (!info.isPresent()) {
            sendError(exchange, 404, "Method not found: " + signature);
            return null;
        }
        return info.get();
    }

    private MethodMeta parseSignature(String signature, HttpExchange exchange) {
        int paren = signature.indexOf('(');
        if (paren == -1) {
            sendError(exchange, 400, "Invalid signature format");
            return null;
        }

        String fullName = signature.substring(0, paren);
        int dot = fullName.lastIndexOf('.');

        if (dot == -1) {
            sendError(exchange, 400, "Invalid signature format");
            return null;
        }

        return new MethodMeta(
            fullName.substring(0, dot),
            fullName.substring(dot + 1)
        );
    }

    private Object createInstance(String className, HttpExchange exchange) {
        try {
            if (injector == null) {
                sendError(exchange, 500, "Injector not available");
                return null;
            }
            Class<?> clazz = Class.forName(className);
            return injector.getInstance(clazz);
        } catch (ClassNotFoundException e) {
            sendError(exchange, 404, "Class not found: " + className);
            return null;
        } catch (ConfigurationException e) {
            sendError(exchange, 404, "Class not bound in Guice: " + className);
            return null;
        } catch (Exception e) {
            sendError(exchange, 500, "Error creating instance: " + e.getMessage());
            return null;
        }
    }
        //try {
        //    return Class.forName(className).getDeclaredConstructor().newInstance();
        //} catch (ClassNotFoundException e) {
        //    sendError(exchange, 404, "Class not found: " + className);
        //    return null;
        //} catch (Exception e) {
        //    sendError(exchange, 500, "Error creating instance: " + e.getMessage());
        //    return null;
        //}
    //}

    private Object[] buildArgs(List<MethodRegistry.ParamInfo> paramInfo, JsonNode params, HttpExchange exchange) {
        Object[] args = new Object[paramInfo.size()];

        for (int i = 0; i < paramInfo.size(); i++) {
            if (i >= params.size()) {
                sendError(exchange, 400, "Missing parameter at index " + i);
                return null;
            }

            try {
                String typeName = cleanTypeName(paramInfo.get(i).typeName);
                Class<?> paramClass = Class.forName(typeName);
                args[i] = deserializeParam(paramClass, params.get(i));
            } catch (ClassNotFoundException e) {
                sendError(exchange, 500, "Parameter type not found: " + paramInfo.get(i).typeName);
                return null;
            } catch (Exception e) {
                sendError(exchange, 500, "Error creating parameter: " + e.getMessage());
                return null;
            }
        }

        return args;
    }

    private String cleanTypeName(String typeName) {
        return typeName.contains(" ") ? typeName.substring(0, typeName.indexOf(" ")) : typeName;
    }

    private Object deserializeParam(Class<?> paramClass, JsonNode paramNode) throws Exception {
        if (paramClass.getName().contains("$")) {
            return createNestedInstance(paramClass, paramNode);
        }
        return mapper.treeToValue(paramNode, paramClass);
    }

    private Object createNestedInstance(Class<?> nestedClass, JsonNode paramNode) {
        // Implementation for nested class instantiation
        throw new UnsupportedOperationException("Nested class creation not implemented");
    }

    private Object invoke(Object instance, String methodName, Object[] args,
                          List<MethodRegistry.ParamInfo> paramInfo, HttpExchange exchange) {
        try {
            Class<?>[] paramTypes = new Class<?>[paramInfo.size()];
            for (int i = 0; i < paramInfo.size(); i++) {
                paramTypes[i] = Class.forName(cleanTypeName(paramInfo.get(i).typeName));
            }

            Method method = instance.getClass().getMethod(methodName, paramTypes);
            return method.invoke(instance, args);
        } catch (NoSuchMethodException e) {
            sendError(exchange, 404, "Method not found: " + methodName);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            sendError(exchange, 500, "Execution error: " + cause.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Invocation error: " + e.getMessage());
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, Object result) {
        try {
            byte[] bytes = result == null ?
                "null".getBytes() : mapper.writeValueAsBytes(result);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            sendError(exchange, 500, "Error sending response");
        }
    }

    private boolean sendError(HttpExchange exchange, int code, String message) {
        try {
            // Create error object without using Java 9+ Map.of()
            ObjectNode errorNode = mapper.createObjectNode();
            errorNode.put("error", message);
            byte[] bytes = mapper.writeValueAsBytes(errorNode);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            try {
                exchange.sendResponseHeaders(500, 0);
            } catch (IOException ignored) {}
        }
        return false;
    }
}
