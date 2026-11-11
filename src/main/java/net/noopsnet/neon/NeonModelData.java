// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.neoforged.neoforge.client.model.data.ModelProperty;

/** Data-only NeoForge API: no renderer or Minecraft client class references. */
public final class NeonModelData {
    private NeonModelData() {}
    public static final ModelProperty<Integer> CONNECTIONS=new ModelProperty<>();
    public static final ModelProperty<Integer> GLOW=new ModelProperty<>();
}
