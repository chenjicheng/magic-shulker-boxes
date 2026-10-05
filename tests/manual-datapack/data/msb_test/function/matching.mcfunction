function msb_test:base
item replace entity @s hotbar.0 with minecraft:shulker_box
item replace entity @s hotbar.1 with minecraft:red_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:dirt",count:1}},{slot:1,item:{id:"minecraft:stone",count:1}}]]
item replace entity @s hotbar.2 with minecraft:blue_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:cobblestone",count:63}}]]
summon minecraft:item ~ ~0.5 ~ {Item:{id:"minecraft:cobblestone",count:5},PickupDelay:20s}
tellraw @s "MSB matching: blue box should contain cobblestone 64 + 4; empty boxes and boxes with unrelated contents stay unchanged."
