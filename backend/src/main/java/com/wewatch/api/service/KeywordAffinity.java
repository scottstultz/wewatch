package com.wewatch.api.service;

// A top keyword across the list (#271). name labels the keyword-seeded shelf
// and is null on rows cached before names were stored — such keywords still
// boost scoring but can't seed a shelf until the cache TTL backfills them.
record KeywordAffinity(int id, String name) {}
