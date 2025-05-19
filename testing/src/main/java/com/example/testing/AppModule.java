package com.example.testing;

import com.google.inject.AbstractModule;

class AppModule extends AbstractModule {
    @Override
    protected void configure() {
        // Bind interfaces to implementations if needed
        // For this example, we're just ensuring proper instantiation
        bind(ExampleClass.class);
        bind(AnotherExampleClass.class);
    }
}
