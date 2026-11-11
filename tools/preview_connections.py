#!/usr/bin/env python3
"""Offline studies of the actual generated joining meshes, not game screenshots."""
from PIL import Image,ImageDraw
from preview import render,font,OUT

sheet=Image.new('RGB',(1840,940),(25,23,30));draw=ImageDraw.Draw(sheet)
draw.text((30,20),'AFTERLIGHT / CONNECTED LIGHT',font=font(32),fill=(246,227,247))
cases=[(0,'Isolated'),(1,'End cap'),(5,'90-degree bend'),(7,'T junction'),(15,'Crossing')]
for row,(form,color) in enumerate([('strip','cyan'),('channel','pink')]):
 for column,(mask,label) in enumerate(cases):
  x=20+360*column;y=95+400*row
  draw.rounded_rectangle((x,y,x+350,y+380),radius=12,fill=(48,39,45),outline=(96,74,89))
  image=render(f'{color}_{form}_connect{mask}');sheet.paste(image,(x-5,y-10),image)
  draw.text((x+15,y+330),label,font=font(18),fill=(243,225,240))
  draw.text((x+15,y+356),'Shelf trim' if row==0 else 'Glow inlay',font=font(13),fill=(186,153,179))
draw.text((30,906),'OFFLINE NATIVE-MODEL RENDERS / illustrative bloom / existing fixed shapes remain available',font=font(17),fill=(166,146,177))
sheet.save(OUT/'connections.png');print(OUT/'connections.png')
