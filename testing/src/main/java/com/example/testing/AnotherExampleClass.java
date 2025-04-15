package com.example.testing;

import id.dendenden.Instrumented;

public class AnotherExampleClass {

    // This method won't be instrumented (no annotation)
    public void regularMethod() {
        System.out.println("Regular method - not instrumented");
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // This method will be instrumented due to method-level annotation
    @Instrumented("Instrumenting only this specific method")
    public void specialMethod() {
        System.out.println("Special method - instrumented");
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
