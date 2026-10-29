package dev.porter.port.upfront.model;

/** A 1.7.10 member (or class, with an empty name and descriptor) that a symbol maps to. */
public record Target(String owner, String name, String desc, boolean isStatic) {
}
