# 0006. Graph coverage = definite, evidenced answer

Status: accepted

The graph acceptance criterion "coverage ≥ 75% of methods used by the fixtures" counts a symbol as covered when the graph returns a definite, evidenced answer: either a mapped target symbol or an explicit `removed` with evidence. Symbols that genuinely do not exist in 1.7.10 (BlockState, BlockPos/Vec3i, Component, InteractionResult, NonNullList, ItemStack.EMPTY/isEmpty/getCount, …) are the rewriter's adapter-rule territory, not graph gaps.

Measured on a large private 1.19.2 mod → 1.7.10: answered 100% (methods 2126, fields 1395); mapped 27%/27% overall, 42%/71% on the reference port classes. Golden precision 0.90 (all 10 misses are unproven renames reported as removed — candidates for hand-written rules, e.g. Level#hasNeighborSignal → World#isBlockIndirectlyGettingPowered, BlockBehaviour#tick → Block#updateTick, Screen → GuiScreen, Tag → NBTBase).

Follow-up: the "mapped" share is tracked as a separate metric in `porter report`; the 10 golden misses become manual rules in rules/composite/1.19.2__1.7.10/.
