package com.android.server.prism;

import android.annotation.NonNull;
import android.os.Bundle;

/**
 * Describes the source of a plane texture.
 *
 * <p>The current milestone uses placeholder text, but the descriptor format is intentionally
 * extensible so future texture sources can be added without changing the control plane.</p>
 */
public final class PrismTextureSource {
    public static final int KIND_PLACEHOLDER_TEXT = 1;
    public static final int KIND_SURFACE_TEXTURE = 2;

    private static final String KEY_KIND = "kind";
    private static final String KEY_LABEL = "label";

    private final int mKind;
    private final String mLabel;

    private PrismTextureSource(int kind, String label) {
        mKind = kind;
        mLabel = label;
    }

    public static @NonNull PrismTextureSource placeholderText(@NonNull String label) {
        return new PrismTextureSource(KIND_PLACEHOLDER_TEXT, label);
    }

    public static @NonNull PrismTextureSource surfaceTexture(@NonNull String label) {
        return new PrismTextureSource(KIND_SURFACE_TEXTURE, label);
    }

    public int getKind() {
        return mKind;
    }

    public @NonNull String getLabel() {
        return mLabel;
    }

    public @NonNull Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_KIND, mKind);
        bundle.putString(KEY_LABEL, mLabel);
        return bundle;
    }

    public static @NonNull PrismTextureSource fromBundle(@NonNull Bundle bundle) {
        return new PrismTextureSource(bundle.getInt(KEY_KIND, KIND_PLACEHOLDER_TEXT),
                bundle.getString(KEY_LABEL, "PrismOS - Virtual Display #1"));
    }
}
