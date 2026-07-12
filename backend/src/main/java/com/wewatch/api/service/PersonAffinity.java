package com.wewatch.api.service;

// A recurring person across the user's titles (#269). director flags which
// label the shelf gets ("Directed by" vs "More with") when the same person
// does both; score drives the top-N cut like keyword frequency does.
record PersonAffinity(int id, String name, double score, boolean director) {}
