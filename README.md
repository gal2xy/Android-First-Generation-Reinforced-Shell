# Android第一代加固壳

第一代壳原理如下图所示：

![](https://raw.githubusercontent.com/gal2xy/blog_img/main/img/202312012216295.png)

所涉及到的程序如下：

- 源程序APK（SourceApk）
- 脱壳程序APK（ShellApk）
- 加壳程序（main.py）

加固方法如下：
1.生成源APK和脱壳APK，放于加壳代码目录下。
2.提取脱壳APK中的dex，修改dex文件名为shellApk，执行加壳程序，得到classes.dex文件（脱壳dex + 源APK）。
3.将得到的classes.dex替换脱壳APK中的dex。
4.重新签名。

详细原理分析请参考文章：https://gal2xy.github.io/2023/12/01/Android第一代加固壳的原理及实现/
