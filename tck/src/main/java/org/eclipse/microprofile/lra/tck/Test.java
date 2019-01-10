package org.eclipse.microprofile.lra.tck;

import java.util.HashMap;
import java.util.Map;

public class Test {
public static void main(String[] args) {
    Map<String,String> m = new HashMap<>();
    m.put("ahoj", "ahoj franto, jak se mas?");
    System.out.println(m.putIfAbsent("ahoj", "nazdar"));
}
}
