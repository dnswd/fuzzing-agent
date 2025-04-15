package com.example.testing;

public class ExampleClass {

    public void doSomething() {
        System.out.println("Doing something...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int doSomethingElse(int value) {
        System.out.println("Doing something else with value: " + value);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return value * 2;
    }
}