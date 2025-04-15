package com.example.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private MethodRegistry methodRegistry;
    private Server(MethodRegistry methodRegistry) {
        this.methodRegistry = methodRegistry;
        startHttpServer();
    }
    public static Server getInstance(MethodRegistry methodRegistry) {
        Server result = instance;
        if (result != null) {
            return result;
        }
        synchronized (Server.class) {
            result = instance;
            if (result == null) {
                instance = result = new Server(methodRegistry);
            }
            return result;
        }
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

        server.createContext("/invoke-method", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method not allowed. Use POST request.");
                return;
            }

            try {
                // Parse the request body
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                JsonNode requestNode = mapper.readTree(exchange.getRequestBody());

                // Extract method identifier and parameters
                if (!requestNode.has("method") || !requestNode.has("params")) {
                    sendErrorResponse(exchange, 400, "Request must include 'method' and 'params' fields");
                    return;
                }

                String methodSignature = requestNode.get("method").asText();
                JsonNode paramsNode = requestNode.get("params");

                // Get parameter info for the method from registry
                Optional<List<MethodRegistry.ParamInfo>> paramInfoOpt = methodRegistry.getParamsForMethod(methodSignature);
                if (!paramInfoOpt.isPresent()) {
                    sendErrorResponse(exchange, 404, "Method not found in registry: " + methodSignature);
                    return;
                }

                List<MethodRegistry.ParamInfo> paramInfoList = paramInfoOpt.get();

                // Parse the class and method name from the signature
                int openParenIndex = methodSignature.indexOf('(');
                if (openParenIndex == -1) {
                    sendErrorResponse(exchange, 400, "Invalid method signature format");
                    return;
                }

                String classAndMethod = methodSignature.substring(0, openParenIndex);
                int lastDotBeforeParen = classAndMethod.lastIndexOf('.');

                if (lastDotBeforeParen == -1) {
                    sendErrorResponse(exchange, 400, "Invalid method signature format");
                    return;
                }

                String className = classAndMethod.substring(0, lastDotBeforeParen);
                String methodName = classAndMethod.substring(lastDotBeforeParen + 1);

                // Get the class and create an instance
                Class<?> targetClass;
                try {
                    targetClass = Class.forName(className);
                } catch (ClassNotFoundException e) {
                    sendErrorResponse(exchange, 404, "Class not found: " + className);
                    return;
                }

                Object instance = targetClass.getDeclaredConstructor().newInstance();

                // Prepare parameters and parameter types for method invocation
                Object[] methodParams = new Object[paramInfoList.size()];
                Class<?>[] paramTypes = new Class<?>[paramInfoList.size()];

                for (int i = 0; i < paramInfoList.size(); i++) {
                    MethodRegistry.ParamInfo paramInfo = paramInfoList.get(i);
                    try {
                        // Extract just the base type name without fields
                        String baseTypeName = paramInfo.typeName;
                        if (baseTypeName.contains(" ")) {
                            baseTypeName = baseTypeName.substring(0, baseTypeName.indexOf(" "));
                        }

                        Class<?> paramClass = Class.forName(baseTypeName);
                        paramTypes[i] = paramClass;

                        // Check if this parameter index exists in the request
                        if (i >= paramsNode.size()) {
                            sendErrorResponse(exchange, 400, "Missing parameter at index " + i);
                            return;
                        }

                        JsonNode paramNode = paramsNode.get(i);

                        // Handle static nested classes (with $ in the name)
                        if (paramClass.getName().contains("$")) {
                            // This is a nested class - try to create it using field values
                            methodParams[i] = createNestedClassInstance(paramClass, paramNode);
                        } else {
                            // Regular class
                            methodParams[i] = mapper.treeToValue(paramNode, paramClass);
                        }
                    } catch (ClassNotFoundException e) {
                        sendErrorResponse(exchange, 500, "Parameter type not found: " + paramInfo.typeName);
                        return;
                    } catch (Exception e) {
                        sendErrorResponse(exchange, 500, "Error creating parameter: " + e.getMessage());
                        return;
                    }
                }

                // Get and invoke the method
                try {
                    Method method = targetClass.getMethod(methodName, paramTypes);
                    Object result = method.invoke(instance, methodParams);

                    // Send the result back
                    byte[] responseBytes;
                    if (result == null) {
                        responseBytes = "null".getBytes();
                    } else {
                        responseBytes = mapper.writeValueAsBytes(result);
                    }

                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                } catch (NoSuchMethodException e) {
                    sendErrorResponse(exchange, 404, "Method not found: " + methodName);
                } catch (InvocationTargetException e) {
                    sendErrorResponse(exchange, 500, "Error in method execution: " +
                        (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                } catch (Exception e) {
                    sendErrorResponse(exchange, 500, "Error invoking method: " + e.getMessage());
                }
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Server error: " + e.getMessage());
            } finally {
                exchange.close();
            }
        });
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
}
