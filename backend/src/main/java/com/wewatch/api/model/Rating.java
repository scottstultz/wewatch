package com.wewatch.api.model;

// Deliberately binary (#273): thumbs get vastly higher participation than
// star scales, and suggestion scoring only needs sign, not magnitude
public enum Rating {
	UP,
	DOWN
}
