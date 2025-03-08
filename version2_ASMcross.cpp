// 关于账号和密码,你需要在编译前修改他们
/* 编译指令:

Windows: clang++ -O3 -march=native -mtune=native -fno-stack-protector -no-pie -flto -ffunction-sections -fdata-sections -fomit-frame-pointer -ffast-math -mfpmath=sse -fno-exceptions -fno-rtti -Wl,--gc-sections -s version2_ASMcorss.cpp -o main.exe -lws2_32 -lkernel32 -fuse-ld=lld

Linux:   clang++ -O3 -march=native -mtune=native -fno-stack-protector -no-pie -flto -ffunction-sections -fdata-sections -fomit-frame-pointer -ffast-math -mfpmath=sse -fno-exceptions -fno-rtti -Wl,--gc-sections -s version2_ASMcross.cpp -o main 
*/

/* 
账号: 学号@运营商
运营商: cmcc(中国移动), njxy(中国电信) ,(如果校园网)那么你需要去除掉@
注意除了你的账户密码里面的内容,不要在""里面留有空格
*/

#define ACCOUNT "B2***0***@cmcc"  // 这里改写你的账号例如B2***0***@cmcc
#define PASSWORD "1234567"        // 这里改写成你的登录密码

#ifdef _WIN32
#include <winsock2.h>
#include <windows.h>
#define SOCKET_CLOSE(s) closesocket(s)
#define EXIT_APP(code) ExitProcess(code)
#define PLATFORM_INIT()                       \
    {                                         \
        WSADATA wsaData;                      \
        WSAStartup(MAKEWORD(2, 2), &wsaData); \
    }
#define PLATFORM_CLEANUP() WSACleanup()
#define SOCKET_ERROR_CODE WSAGetLastError()
#define INVALID_SOCK INVALID_SOCKET
#define SOCKET_T SOCKET
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#define SOCKET_CLOSE(s) close(s)
#define EXIT_APP(code) exit(code)
#define PLATFORM_INIT()
#define PLATFORM_CLEANUP()
#define SOCKET_ERROR_CODE errno
#define INVALID_SOCK (-1)
#define SOCKET_T int
#endif

#include <stdint.h>

// 平台无关的常量定义
#ifdef _WIN32
#if defined(__clang__)
#define USING_CLANG 1
#elif defined(_MSC_VER)
#define USING_MSVC 1
#pragma optimize("gts", on)
#pragma runtime_checks("", off)
#pragma strict_gs_check(off)
#pragma check_stack(off)
#pragma intrinsic(memcpy, memset, strcmp)
#endif
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "kernel32.lib")
#endif

// 使用编译期常量 - 恢复为constexpr而不是static const以保持一致性
#ifdef _WIN32
constexpr DWORD SERVER_IP_DWORD = 0x0BF40A0A;  // 10.10.244.11 (网络字节序)
constexpr USHORT SERVER_PORT = 801;
constexpr DWORD TIMEOUT_MS = 10;  // 减少超时时间
#else
constexpr uint32_t SERVER_IP_DWORD = 0x0BF40A0A;  // 10.10.244.11 (网络字节序)
constexpr uint16_t SERVER_PORT = 801;
constexpr uint32_t TIMEOUT_MS = 10;  // 减少超时时间
#endif

// 合并所有HTTP请求为一个数组，减少发送次数 - 恢复使用constexpr
#if defined(_WIN32) && !defined(__GNUC__)
__declspec(align(16)) constexpr unsigned char HTTP_REQUEST[] =
#else
__attribute__((aligned(16))) constexpr unsigned char HTTP_REQUEST[] =
#endif
    "GET /eportal/portal/login?login_method=1&user_account=,0," ACCOUNT "&user_password=" PASSWORD
    " HTTP/1.1\r\n"
    "Host: 10.10.244.11:801\r\n"
    "Connection: close\r\n"
    "Accept: */*\r\n"
    "\r\n";

// 跨平台的优化连接函数
#if defined(_WIN32) && defined(USING_MSVC)
__forceinline int LowLevelConnect(SOCKET_T s, const struct sockaddr* name, int namelen)
{
    int result;
    __asm {
        mov eax, s
        push namelen
        push name
        push eax
        call connect
        mov result, eax
        add esp, 12  // 优化栈操作
    }
    return result;
}
#else
inline int LowLevelConnect(SOCKET_T s, const struct sockaddr* name, int namelen)
{
    return connect(s, name, namelen);
}
#endif

// 跨平台的优化发送函数
#if defined(_WIN32) && defined(USING_MSVC)
__forceinline int LowLevelSend(SOCKET_T s)
{
    int result;
    __asm {
        mov eax, s
        push 0
        push sizeof(HTTP_REQUEST)
        push offset HTTP_REQUEST
        push eax
        call send
        mov result, eax
        add esp, 16
    }
    return result;
}
#else
inline int LowLevelSend(SOCKET_T s)
{
    return send(s, (const char*)HTTP_REQUEST, sizeof(HTTP_REQUEST) - 1, 0);  // 注意减1，排除终止符
}
#endif

// 跨平台主函数
#ifdef _WIN32
#pragma comment(linker, "/SUBSYSTEM:WINDOWS /ENTRY:WinMainStart")
extern "C" __attribute__((noreturn)) void __stdcall WinMainStart()
#else
int main()
#endif
{
    // 平台初始化
    PLATFORM_INIT();

// 创建Socket - 使用与原始代码相同的直接数字常量以保持一致性
#ifdef _WIN32
    SOCKET_T sock = socket(2 /*AF_INET*/, 1 /*SOCK_STREAM*/, 6 /*IPPROTO_TCP*/);
#else
    SOCKET_T sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
#endif

    if (sock == INVALID_SOCK)
    {
        EXIT_APP(1);
    }

// TCP_NODELAY禁用Nagle算法，加速小数据包传输
#ifdef _WIN32
    BOOL optval = TRUE;
#else
    int optval = 1;
#endif
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char*)&optval, sizeof(optval));

// 设置超时 - 与原始版本保持一致
#ifdef _WIN32
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&TIMEOUT_MS, sizeof(TIMEOUT_MS));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&TIMEOUT_MS, sizeof(TIMEOUT_MS));
#else
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = TIMEOUT_MS * 1000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof(tv));
#endif

    // 创建服务器地址结构 - 移除memset，与原始版本保持一致
    struct sockaddr_in serverAddr;
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(SERVER_PORT);
    serverAddr.sin_addr.s_addr = SERVER_IP_DWORD;  // 预计算的IP地址

    // 连接服务器
    LowLevelConnect(sock, (struct sockaddr*)&serverAddr, sizeof(serverAddr));

    // 发送HTTP请求
    LowLevelSend(sock);

    // 清理资源
    SOCKET_CLOSE(sock);
    PLATFORM_CLEANUP();

// 退出
#ifdef _WIN32
    EXIT_APP(0);
#else
    return 0;
#endif
}

#if defined(_WIN32) && defined(_DEBUG)
int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow)
{
    WinMainStart();
    return 0;
}

int main()
{
    WinMainStart();
    return 0;
}
#endif