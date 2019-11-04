#!/usr/bin/env groovy
package com.definex.dso.utils

public class Event {
    private static Map<String, List<Closure>> eventMap = new HashMap<>();

    public static void publish(String eventName) {
        List<Closure> events = eventMap.get(eventName)
        if (events != null) {
            for (Closure event : events) {
                event.call();
            }
        }
    }

    public static void subscribe(String eventName, Closure event) {
        if (eventMap.containsKey(eventName)) {
            eventMap.get(eventName).add(event);
        } else {
            eventMap.put(eventName, [event]);
        }
    }
}