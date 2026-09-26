function msb_test:base
fill 97 100 97 105 100 105 minecraft:stone
fill 97 101 97 105 105 105 minecraft:air
tp @s 101.5 101 103.5 180 25
item replace entity @s hotbar.0 with minecraft:diamond_pickaxe
item replace entity @s inventory.0 with minecraft:blue_shulker_box 16
item replace entity @s inventory.1 with minecraft:red_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:cobblestone",count:12}},{slot:1,item:{id:"minecraft:oak_planks",count:12}},{slot:2,item:{id:"minecraft:glass",count:12}}]]
tellraw @s "MSB refill: full inventory, 16 Carpet empty boxes, and one material box. Load MSB-Refill at 100,101,100. Easy Place must extract and place without losing the pickaxe or stacked boxes."
