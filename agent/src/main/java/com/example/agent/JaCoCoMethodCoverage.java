package com.example.agent;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility to invoke methods with JaCoCo code coverage.
 * Can be used with the MethodInvoker to track coverage of invoked methods.
 */
public class JaCoCoMethodCoverage {

    private PrintStream outputStream;

    /**
     * Contains coverage data for classes analyzed in the last run
     */
    private Map<String, IClassCoverage> lastRunCoverage;

    /**
     * Creates a new coverage utility instance.
     *
     * @param outputStream stream for coverage output (or null for no output)
     */
    public JaCoCoMethodCoverage(PrintStream outputStream) {
        this.outputStream = outputStream != null ? outputStream : System.out;
        this.lastRunCoverage = new HashMap<>();
    }

    /**
     * Custom class loader that loads instrumented classes.
     */
    private static class InstrumentingClassLoader extends ClassLoader {
        private final Map<String, byte[]> instrumentedClasses = new HashMap<>();
        private final IRuntime runtime;

        InstrumentingClassLoader(IRuntime runtime) {
            this.runtime = runtime;
        }

        void addInstrumentedClass(String name, byte[] bytes) {
            instrumentedClasses.put(name, bytes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = instrumentedClasses.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }

            // If the class needs instrumentation, do it now
            if (!name.startsWith("java.") && !name.startsWith("sun.") &&
                !name.startsWith("org.jacoco.") && !name.startsWith("jdk.")) {
                try {
                    // Get the original class bytes
                    String resourceName = "/" + name.replace('.', '/') + ".class";
                    InputStream is = getClass().getResourceAsStream(resourceName);

                    // If we can't find the class, delegate to parent
                    if (is == null) {
                        return super.loadClass(name, resolve);
                    }

                    // Read the class bytes
                    byte[] originalBytes = readAllBytes(is);
                    is.close();

                    // Instrument the class
                    Instrumenter instrumenter = new Instrumenter(runtime);
                    byte[] instrumentedBytes = instrumenter.instrument(
                        new ByteArrayInputStream(originalBytes), name);

                    // Add to our cache and define the class
                    instrumentedClasses.put(name, instrumentedBytes);
                    return defineClass(name, instrumentedBytes, 0, instrumentedBytes.length);
                } catch (IOException e) {
                    // If instrumentation fails, fall back to parent class loader
                    return super.loadClass(name, resolve);
                }
            }

            return super.loadClass(name, resolve);
        }

        private byte[] readAllBytes(InputStream is) throws IOException {
            byte[] buffer = new byte[8192];
            int bytesRead;
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            while ((bytesRead = is.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        }
    }

    /**
     * Results from a method invocation with coverage.
     */
    public static class CoverageInvocationResult<T> {
        private final T result;
        private final Map<String, IClassCoverage> coverageData;
        private final Throwable error;

        CoverageInvocationResult(T result, Map<String, IClassCoverage> coverageData) {
            this.result = result;
            this.coverageData = coverageData;
            this.error = null;
        }

        CoverageInvocationResult(Throwable error, Map<String, IClassCoverage> coverageData) {
            this.result = null;
            this.coverageData = coverageData;
            this.error = error;
        }

        public T getResult() {
            return result;
        }

        public Map<String, IClassCoverage> getCoverageData() {
            return coverageData;
        }

        public boolean hasError() {
            return error != null;
        }

        public Throwable getError() {
            return error;
        }
    }

    /**
     * Invokes a method with JaCoCo coverage instrumentation.
     *
     * @param <T> Return type of the method
     * @param className The name of the class containing the method
     * @param methodName The name of the method to invoke
     * @param paramTypes Array of parameter types
     * @param args Array of arguments
     * @param printCoverage Whether to print coverage info to output stream
     * @return Result object containing both method result and coverage data
     * @throws Exception if any error occurs during instrumentation or invocation
     */
    @SuppressWarnings("unchecked")
    public <T> CoverageInvocationResult<T> invokeWithCoverage(
        String className,
        String methodName,
        Class<?>[] paramTypes,
        Object[] args,
        boolean printCoverage) throws Exception {

        // Set up JaCoCo runtime and instrumentation
        IRuntime jacocoRuntime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        jacocoRuntime.startup(data);

        try {
            // Create our instrumenting class loader
            InstrumentingClassLoader loader = new InstrumentingClassLoader(jacocoRuntime);

            // Load the target class through our instrumenting class loader
            Class<?> targetClass = loader.loadClass(className);

            // Create an instance and get the method
            Object instance = targetClass.getDeclaredConstructor().newInstance();
            Method method = targetClass.getMethod(methodName, paramTypes);

            // Execute the method and capture the result
            AtomicReference<Object> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            try {
                T result = (T) method.invoke(instance, args);
                resultRef.set(result);
            } catch (Throwable e) {
                // Unwrap reflection exceptions
                if (e.getCause() != null) {
                    errorRef.set(e.getCause());
                } else {
                    errorRef.set(e);
                }
            }

            // Collect coverage data
            ExecutionDataStore executionData = new ExecutionDataStore();
            SessionInfoStore sessionInfos = new SessionInfoStore();
            data.collect(executionData, sessionInfos, false);

            // Analyze coverage
            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(executionData, coverageBuilder);

            // Analyze the class and its dependencies
            String resourceName = "/" + className.replace('.', '/') + ".class";
            try (InputStream original = getClass().getResourceAsStream(resourceName)) {
                analyzer.analyzeClass(original, className);
            }

            // Build the coverage data map
            Map<String, IClassCoverage> coverageMap = new HashMap<>();
            for (IClassCoverage cc : coverageBuilder.getClasses()) {
                coverageMap.put(cc.getName(), cc);
            }

            // Store for later access
            this.lastRunCoverage = coverageMap;

            // Optionally print coverage information
            if (printCoverage) {
                printCoverageInfo(coverageMap);
            }

            // Return results
            if (errorRef.get() != null) {
                return new CoverageInvocationResult<>(errorRef.get(), coverageMap);
            } else {
                return new CoverageInvocationResult<>((T) resultRef.get(), coverageMap);
            }

        } finally {
            // Shutdown JaCoCo runtime
            jacocoRuntime.shutdown();
        }
    }

    /**
     * Gets coverage data from the last executed run.
     *
     * @return Map of class coverage information by class name
     */
    public Map<String, IClassCoverage> getLastRunCoverage() {
        return new HashMap<>(lastRunCoverage);
    }

    /**
     * Prints coverage information to the configured output stream.
     *
     * @param coverageMap Map of class coverage objects
     */
    public void printCoverageInfo(Map<String, IClassCoverage> coverageMap) {
        for (IClassCoverage cc : coverageMap.values()) {
            outputStream.printf("Coverage of class %s%n", cc.getName());

            printCounter("instructions", cc.getInstructionCounter());
            printCounter("branches", cc.getBranchCounter());
            printCounter("lines", cc.getLineCounter());
            printCounter("methods", cc.getMethodCounter());
            printCounter("complexity", cc.getComplexityCounter());

            outputStream.println("Line coverage:");
            for (int i = cc.getFirstLine(); i <= cc.getLastLine(); i++) {
                outputStream.printf("Line %s: %s%n", i,
                    getCoverageStatus(cc.getLine(i).getStatus()));
            }
            outputStream.println();
        }
    }

    private void printCounter(String unit, ICounter counter) {
        int missed = counter.getMissedCount();
        int total = counter.getTotalCount();
        double coverage = total > 0 ? (total - missed) * 100.0 / total : 0;

        outputStream.printf("%s: %d of %d covered (%.1f%%)%n",
            unit, (total - missed), total, coverage);
    }

    private String getCoverageStatus(int status) {
        switch (status) {
            case ICounter.NOT_COVERED:
                return "NOT COVERED";
            case ICounter.PARTLY_COVERED:
                return "PARTLY COVERED";
            case ICounter.FULLY_COVERED:
                return "FULLY COVERED";
            default:
                return "UNKNOWN";
        }
    }
}