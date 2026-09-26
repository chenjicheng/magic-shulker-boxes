function msb_test:base
item replace entity @s hotbar.0 with minecraft:blue_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:dirt",count:1}}]]
summon minecraft:item ~ ~0.5 ~ {Item:{id:"minecraft:cobblestone",count:5},PickupDelay:20s}
tellraw @s "MSB fallback: default leaves 5 cobblestone on ground; allowOtherSingleTypeBoxes=true puts them beside dirt."
