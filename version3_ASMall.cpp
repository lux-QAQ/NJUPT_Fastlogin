#include <winsock2.h>
#include <windows.h>

// !!注意!!:此文件只能在MSVC下编译,建议使用vs studio

#define ACCOUNT "B2***0***@cmcc"  // 这里改写你的账号例如B2***0***@cmcc
#define PASSWORD "1234567"        // 这里改写成你的登录密码
#pragma comment(lib, "ws2_32.lib")

// 预计算常量
constexpr DWORD SERVER_IP = 0x0BF40A0A;  // 10.10.244.11 (网络字节序)
constexpr USHORT SERVER_PORT = 0x2103;   // 801 (网络字节序)

// HTTP请求数据
__declspec(align(16)) const char HTTP_REQUEST[] =
    "GET /eportal/portal/login?login_method=1&user_account=,0," ACCOUNT "&user_password=" PASSWORD
    " HTTP/1.1\r\n"
    "Host: 10.10.244.11:801\r\n"
    "Connection: close\r\n"
    "Accept: */*\r\n"
    "\r\n";

// 入口点
extern "C" void __cdecl start()
{
    WSADATA wsaData;
    SOCKET sock = INVALID_SOCKET;
    // 预计算一些常量值
    constexpr int REQUEST_SIZE = sizeof(HTTP_REQUEST) - 1;
    constexpr int INVALID_SOCK_VALUE = -1;  // INVALID_SOCKET的实际值

    // 使用Intel语法的内联汇编
    __asm {
        // 初始化WSA
        push 0
        lea eax, wsaData
        push eax
        push 0x0202; MAKEWORD(2, 2)
        call WSAStartup
        add esp, 12

                                             // 创建Socket
        push 6; IPPROTO_TCP
        push 1; SOCK_STREAM
        push 2; AF_INET
        call socket
        add esp, 12
        mov sock, eax

            // 检查socket是否有效
        cmp eax, INVALID_SOCK_VALUE
        je cleanup

                // 设置TCP_NODELAY
        push 4
        mov eax, 1; TRUE
        push eax
        push 1; TCP_NODELAY
        push 6; IPPROTO_TCP
        push sock
        call setsockopt
        add esp, 20

        // 设置接收超时
        push 4
        mov eax, 10; 超时值10ms
        push eax
        push 0x1006; SO_RCVTIMEO
        push 0xFFFF; SOL_SOCKET
        push sock
        call setsockopt
        add esp, 20

        // 设置发送超时
        push 4
        mov eax, 10; 超时值10ms
        push eax
        push 0x1005; SO_SNDTIMEO
        push 0xFFFF; SOL_SOCKET
        push sock
        call setsockopt
        add esp, 20
    }

    // 创建地址结构
    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = SERVER_PORT;
    addr.sin_addr.s_addr = SERVER_IP;

    __asm {
        // 连接服务器
        push 16; sizeof(sockaddr_in)
        lea eax, addr
        push eax
        push sock
        call connect
        add esp, 12

               // 发送HTTP请求
        push 0; flags
        push REQUEST_SIZE
        lea eax, HTTP_REQUEST
        push eax
        push sock
        call send
        add esp, 16

        // 关闭socket
        push sock
        call closesocket
        add esp, 4

        cleanup:
        // 清理WSA
        call WSACleanup

            // 退出进程
            push 0
            call ExitProcess
    }
}

// 保持原有入口点
#pragma comment(linker, "/SUBSYSTEM:WINDOWS /ENTRY:mainCRTStartup")
int main()
{
    start();
    return 0;
}