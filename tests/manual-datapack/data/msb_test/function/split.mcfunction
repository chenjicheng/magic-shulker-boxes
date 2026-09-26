function msb_test:base
item replace entity @s hotbar.0 with minecraft:blue_shulker_box[minecraft:custom_name="MSB split test"] 16
item replace entity @s hotbar.5 with minecraft:air
summon minecraft:item ~ ~0.5 ~ {Item:{id:"minecraft:cobblestone",count:5},PickupDelay:20s}
tellraw @s "MSB split (onlyWhenInventoryFull=false): slot 1 has 15 empty boxes; slot 6 has one blue box containing 5 cobblestone."
