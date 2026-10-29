package dev.porter.port.upfront.model;

/** A scored candidate that was not chosen (top 3 per row). */
public record Alt(String owner, String name, String desc, double score) {
}
