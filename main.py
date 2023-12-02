from zlib import adler32
from hashlib import sha1
from binascii import unhexlify

def fixCheckSum(dexBytesArray):
    # dexfile[8:12]
    # 小端存储
    value = adler32(bytes(dexBytesArray[12:]))
    valueArray = bytearray(value.to_bytes(4, 'little'))
    for i in range(len(valueArray)):
        dexBytesArray[8 + i] = valueArray[i]


def fixSignature(dexBytesArray):
    # dexfile[12:32]
    sha_1 = sha1()
    sha_1.update(bytes(dexBytesArray[32:]))
    value = sha_1.hexdigest()
    valueArray = bytearray(unhexlify(value))
    for i in range(len(valueArray)):
        dexBytesArray[12 + i] = valueArray[i]



def fixFileSize(dexBytesArray, fileSize):
    # dexfile[32:36]
    # 小端存储
    fileSizeArray = bytearray(fileSize.to_bytes(4, "little"))
    for i in range(len(fileSizeArray)):
        dexBytesArray[32 + i] = fileSizeArray[i]

def encrypto(file):
    for i in range(len(file)):
        file[i] ^= 0xff

    return file



def start():
    # 读取源程序apk, 转成byte数组，方便后续修改
    with open(r'SourceApk.apk', 'rb') as f:
        SourceApkArray = bytearray(f.read())
    # 读取壳程序dex
    with open(r'shellApk.dex', 'rb') as f:
        shellDexArray = bytearray(f.read())

    SourceApkLen = len(SourceApkArray)
    shellDexLen = len(shellDexArray)
    # 新的dex文件长度
    newDexLen = shellDexLen + SourceApkLen + 4
    # 加密源文件
    enApkArray = encrypto(SourceApkArray)
    # 新的dex文件内容 = 壳dex + 加密的源apk + 四字节标识加密后源apk大小长度
    newDexArray = shellDexArray + enApkArray + bytearray(SourceApkLen.to_bytes(4, 'little'))

    # 首先修改filesize
    fixFileSize(newDexArray, newDexLen)
    # 其次修改signature
    fixSignature(newDexArray)
    # 最后修改checksum
    fixCheckSum(newDexArray)

    # 导出文件
    with open(r'classes.dex', 'wb') as f:
        f.write(bytes(newDexArray))

if __name__ == '__main__':
    start()