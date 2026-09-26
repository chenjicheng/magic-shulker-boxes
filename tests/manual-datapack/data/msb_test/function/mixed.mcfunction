function msb_test:base
item replace entity @s hotbar.0 with minecraft:shulker_box 16
item replace entity @s hotbar.1 with minecraft:red_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:dirt",count:1}},{slot:1,item:{id:"minecraft:stone",count:1}}]]
summon minecraft:item ~ ~0.5 ~ {Item:{id:"minecraft:cobblestone",count:5},PickupDelay:20s}
tellraw @s "MSB mixed: 16 empty boxes stay stacked; red box should gain 5 cobblestone."
