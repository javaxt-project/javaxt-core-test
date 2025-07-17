package javaxt.core.test;
import java.util.*;

import static javaxt.utils.Console.console;

public class Main {

    public static void main(String[] arguments) {
        HashMap<String, String> args = console.parseArgs(arguments);

        //System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("Java Version: " + javaxt.utils.Java.getVersion());
        javaxt.io.Jar jar = new javaxt.io.Jar(javaxt.io.Jar.class);
        System.out.println("JavaXT: " + jar.getVersion());


        String test = args.get("-test");
        if (test==null) return;
        else test = test.toLowerCase();

        
    }
}