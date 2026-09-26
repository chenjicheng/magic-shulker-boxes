function msb_test:base
item replace entity @s hotbar.0 with minecraft:blue_shulker_box[minecraft:custom_name={text:'MSB auto space'}] 16
item replace entity @s inventory.0 with minecraft:stone 3
summon minecraft:item ~ ~0.5 ~ {Item:{id:"minecraft:cobblestone",count:5},PickupDelay:20s}
summon minecraft:item ~ ~0.5 ~ {Item:{id:"minecraft:gravel",count:7},PickupDelay:40s}
tellraw @s {text:"MSB auto space: 15 empty boxes + 1 box with stone 3, cobblestone 5, gravel 7. No lost items."}
