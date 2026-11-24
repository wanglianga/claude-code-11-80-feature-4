package com.flood.statemigrate.service;

/** 分区→道路/泵站/河段映射，key=关键分区 */
public record PartitionConfig(int partitionNo, String roadId, String pumpId, String reachId, boolean key) {
}
