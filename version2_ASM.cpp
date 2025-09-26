#include <winsock2.h>
#include <windows.h>
#include <stdint.h>

// 关于账号和密码,你需要在编译前修改他们
/* 编译指令:
clang++ -O3 -march=native -mtune=native  -fno-stack-protector -no-pie   -flto -ffunction-sections -fdata-sections -fomit-frame-pointer -ffast-math -mfpmath=sse -fno-exceptions -fno-rtti  -s version2_ASM.cpp -o main.exe -lws2_32 -lkernel32 -fuse-ld=lld */

/* 

账号: 学号@运营商
运营商: cmcc(中国移动),  njxy(中国电信) ,(如果校园网)那么你需要去除掉@(注意测试时只测试未测试过这种情况) 

注意除了你的账户密码里面的内容,不要在""里面留有空格

*/

#define ACCOUNT "B2***0***@cmcc"  // 这里改写你的账号例如B2***0***@cmcc
#define PASSWORD "1234567"        // 这里改写成你的登录密码

// 避免包含额外头文件
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "kernel32.lib")

#if defined(__clang__)
#define USING_CLANG 1
#elif defined(_MSC_VER)
#define USING_MSVC 1
#pragma optimize("gts", on)                // 全局优化，快速代码，小代码，优化inline函数
#pragma runtime_checks("", off)            // 关闭运行时检查
#pragma strict_gs_check(off)               // 关闭GS安全检查
#pragma check_stack(off)                   // 关闭栈检查
#pragma intrinsic(memcpy, memset, strcmp)  // 使用内建函数
#endif

// 使用编译期常量
constexpr DWORD SERVER_IP_DWORD = 0x0BF40A0A;  // 10.10.244.11 (网络字节序)
constexpr USHORT SERVER_PORT = 801;
constexpr DWORD TIMEOUT_MS = 10;  // 减少超时时间

// 合并所有HTTP请求为一个数组，减少发送次数
__declspec(align(16)) constexpr unsigned char HTTP_REQUEST[] =
    "GET /eportal/portal/login?login_method=1&user_account=,0," ACCOUNT "&user_password=" PASSWORD
    " HTTP/1.1\r\n"
    "Host: 10.10.244.11:801\r\n"
    "Connection: close\r\n"
    "Accept: */*\r\n"
    "\r\n";

// 强制内联函数
__forceinline int LowLevelConnect(SOCKET s, const struct sockaddr* name, int namelen)
{
#if defined(USING_MSVC)
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
#else
    return connect(s, name, namelen);
#endif
}

// 强制内联优化的发送函数
__forceinline int LowLevelSend(SOCKET s)
{
#if defined(USING_MSVC)
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
#else
    return send(s, (const char*)HTTP_REQUEST, sizeof(HTTP_REQUEST), 0);
#endif
}

// 优化启动时间的入口点
#pragma comment(linker, "/SUBSYSTEM:WINDOWS /ENTRY:WinMainStart")
extern "C" __attribute__((noreturn)) void __stdcall WinMainStart()
{
    // 使用汇编直接初始化关键变量，避免C++运行时
    WSADATA wsaData;
    SOCKET sock = INVALID_SOCKET;

    // Winsock初始化 - 直接使用
    WSAStartup(MAKEWORD(2, 2), &wsaData);

    // 创建Socket - 直接使用AF_INET常量避免变量查找
    sock = socket(2 /*AF_INET*/, 1 /*SOCK_STREAM*/, 6 /*IPPROTO_TCP*/);

    // TCP_NODELAY禁用Nagle算法，加速小数据包传输
    BOOL optval = TRUE;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char*)&optval, sizeof(optval));

    // 优化3：降低超时时间
    DWORD timeout = 10;  // 100ms
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char*)&timeout, sizeof(timeout));

    // 设置超时 - 缩短超时时间
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&TIMEOUT_MS, sizeof(TIMEOUT_MS));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&TIMEOUT_MS, sizeof(TIMEOUT_MS));

    // 创建服务器地址结构 - 使用预计算的IP地址值
    struct sockaddr_in serverAddr;
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(SERVER_PORT);
    serverAddr.sin_addr.s_addr = SERVER_IP_DWORD;  // 预计算的IP地址

    // 连接服务器
    LowLevelConnect(sock, (struct sockaddr*)&serverAddr, sizeof(serverAddr));

    // 一次性发送整个请求
    LowLevelSend(sock);

    // 立即清理资源
    closesocket(sock);
    WSACleanup();

    // 发送成功就立即退出，不等待响应
    ExitProcess(0);
}

// 保留标准入口点以便调试
#ifdef _DEBUG
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
