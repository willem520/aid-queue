#author Bob
#!/bin/bash

echo ">开始提取图片..."
path=`pwd`
assets_path='./*.xcassets'
ls $assets_path | while read line
do
    imageset_path="$assets_path/$line"
    ls $imageset_path | while read line2
    do
        suffix=${line2##*.}
        if [ $suffix = "png" ] 
        then
            image_path="$imageset_path/$line2"
            mv $image_path $path
            echo "移动图片 ${line2} 到 ${path}"
        fi
    done
done
echo ">图片提取完成"
