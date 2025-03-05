/* 编译指令:
Windows: clang++ -O3 -march=native -mtune=native -fno-stack-protector -no-pie -flto -ffunction-sections -fdata-sections -fomit-frame-pointer -ffast-math -mfpmath=sse -fno-exceptions -fno-rtti -Wl,--gc-sections -s version1_APIcross.cpp -o main.exe -lws2_32 -lkernel32 -fuse-ld=lld
Linux:   clang++ -O3 -march=native -mtune=native -fno-stack-protector -no-pie -flto -ffunction-sections -fdata-sections -fomit-frame-pointer -ffast-math -mfpmath=sse -fno-exceptions -fno-rtti -Wl,--gc-sections -s version1_APIcross.cpp -o main 
*/

/* 
账号: 学号@运营商
运营商: cmcc(中国移动), njxy(中国电信) ,(如果校园网)那么你需要去除掉@
注意除了你的账户密码里面的内容,不要在""里面留有空格
*/

#define ACCOUNT "B2***0***@cmcc" // 这里改写你的账号例如B2***0***@cmcc
#define PASSWORD "1234567" // 这里改写成你的登录密码

// 跨平台头文件和定义
#ifdef _WIN32
    #include <winsock2.h>
    #include <ws2tcpip.h>
    #include <windows.h>
    #pragma comment(lib, "ws2_32.lib")
    
    #define SOCKET_T SOCKET
    #define INVALID_SOCK INVALID_SOCKET
    #define SOCKET_ERROR_VAL SOCKET_ERROR
    #define SOCKET_CLOSE(s) closesocket(s)
    #define PLATFORM_INIT() { WSADATA wsaData; WSAStartup(MAKEWORD(2, 2), &wsaData); }
    #define PLATFORM_CLEANUP() WSACleanup()
#else
    #include <sys/socket.h>
    #include <netinet/in.h>
    #include <arpa/inet.h>
    #include <unistd.h>
    #include <errno.h>
    #include <string.h>
    #include <stdlib.h>
    #include <stdio.h>
    
    #define SOCKET_T int
    #define INVALID_SOCK (-1)
    #define SOCKET_ERROR_VAL (-1)
    #define SOCKET_CLOSE(s) close(s)
    #define PLATFORM_INIT()
    #define PLATFORM_CLEANUP()
#endif

#include <string.h>

// 常量定义，使用constexpr优化
constexpr char SERVER_IP[] = "10.10.244.11";
constexpr unsigned short SERVER_PORT = 801;
constexpr unsigned int TIMEOUT_MS = 3000;
constexpr size_t BUFFER_SIZE = 4096;

// 预定义HTTP请求部分，使用constexpr优化
constexpr const char* REQ_PART1 = "GET /eportal/portal/login?login_method=1";
constexpr const char* REQ_PART2 = "&user_account=,0," ACCOUNT;
constexpr const char* REQ_PART3 = "&user_password=" PASSWORD " HTTP/1.1\r\n";
constexpr const char* REQ_PART4 = "Host: 10.10.244.11:801\r\nConnection: close\r\n";
constexpr const char* REQ_PART5 = "Accept: */*\r\n\r\n";

// 通用的网络初始化函数
int send_login_request() {
    SOCKET_T sock = INVALID_SOCK;
    int result = 0;
    
    // 平台初始化
    PLATFORM_INIT();
    
    // 创建Socket
    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCK) {
        PLATFORM_CLEANUP();
        return 1;
    }
    
    // 直接使用IP地址连接
    struct sockaddr_in serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(SERVER_PORT);
    
    // 跨平台的IP地址转换
    #ifdef _WIN32
        inet_pton(AF_INET, SERVER_IP, &(serverAddr.sin_addr));
    #else
        serverAddr.sin_addr.s_addr = inet_addr(SERVER_IP);
    #endif
    
    // 设置超时 - 连接更快失败
    #ifdef _WIN32
        int timeout = TIMEOUT_MS;
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char*)&timeout, sizeof(timeout));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char*)&timeout, sizeof(timeout));
    #else
        struct timeval tv;
        tv.tv_sec = TIMEOUT_MS / 1000;
        tv.tv_usec = (TIMEOUT_MS % 1000) * 1000;
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char*)&tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char*)&tv, sizeof(tv));
    #endif
    
    // 连接服务器
    if (connect(sock, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) != 0) {
        SOCKET_CLOSE(sock);
        PLATFORM_CLEANUP();
        return 1;
    }
    
    // 构建完整请求
    static char request[1024]; // 足够大以容纳请求
    strcpy(request, REQ_PART1);
    strcat(request, REQ_PART2);
    strcat(request, REQ_PART3);
    strcat(request, REQ_PART4);
    strcat(request, REQ_PART5);
    
    // 一次发送所有数据
    int sendResult = send(sock, request, strlen(request), 0);
    if (sendResult == SOCKET_ERROR_VAL) {
        result = 1;
    }
    
    // 清理资源
    SOCKET_CLOSE(sock);
    PLATFORM_CLEANUP();
    
    return result;
}

#ifdef _WIN32
// Windows入口点
int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    return send_login_request();
}

#ifdef _DEBUG
int main(void) {
    return WinMain(GetModuleHandle(NULL), NULL, NULL, SW_HIDE);
}
#endif
#else
// Linux入口点
int main() {
    return send_login_request();
}
#endif