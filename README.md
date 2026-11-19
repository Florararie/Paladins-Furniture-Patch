# Polymer Patch for Paladin's Furniture

Server-side-only Paladin's Furniture: the mod stays installed on the server, and clients without it
are shown the furniture through Polymer instead of the mod's own blocks and entities.

Every `pfm` block keeps its real server-side behaviour - collision, sitting, containers, GUIs - and
the client receives

## Where the assets come from

Paladin's Furniture only ships blockstates and models for the furniture that does not vary by
material. Everything that varies by wood or stone variant is generated at runtime by
`PFMBlockstateModelProvider`, which is client code: it is built on `net.minecraft.client.data.models`
and cannot run on a dedicated server.

Instead of shipping a generated pack, the patch rebuilds the per-variant blockstates and item
definitions at server start from the live block registry. `PFMGeneratedAssets` supplies the small
family/facing/axis table PFM's generator would have written, and `PFMBlockStates` resolves those
entries against the mod's own templates and textures in its jar. The result is added directly to
the Polymer resource pack; no generated JSON is stored in this jar.

A pack in `<server>/pfm/cache/pfm-assetpack` is still used when present, so a newer pack can be
supplied without rebuilding the patch. Without one, the registry-generated assets are used.

## Current state

Covered: all 1678 furniture blocks and 15531 block states are virtualised with collision stand-ins,
models resolve for every state from the registry-generated assets, items and creative tabs are
served, and the mod's chair entities are replaced with invisible seats.

Not done yet: drawing container contents (fridges, freezers, ovens, stoves, cabinets, plates),
which needs item displays per container block entity, and checking seat heights against the mod's own
chair entities on a real client.
