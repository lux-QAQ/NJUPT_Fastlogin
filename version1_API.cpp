#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>

/* 编译指令:
clang++ -O3 -march=native -mtune=native  -fno-stack-protector -no-pie   -flto -ffunction-sections -fdata-sections -fomit-frame-pointer -ffast-math -mfpmath=sse -fno-exceptions -fno-rtti -Wl,--gc-sections -s version1_API.cpp -o main.exe -lws2_32 -lkernel32 -fuse-ld=lld */

/* 

账号: 学号@运营商
运营商: cmcc(中国移动),  njxy(中国电信) ,(如果校园网)那么你需要去除掉@(注意测试时只测试未测试过这种情况) 

注意除了你的账户密码里面的内容,不要在""里面留有空格

*/

#define ACCOUNT "B2***0***@cmcc" // 这里改写你的账号例如B2***0***@cmcc
#define PASSWORD "1234567" // 这里改写成你的登录密码

#pragma comment(lib, "ws2_32.lib")

// 常量定义，使用constexpr优化
constexpr char SERVER_IP[] = "10.10.244.11";
constexpr USHORT SERVER_PORT = 801;
constexpr DWORD TIMEOUT_MS = 3000;
constexpr size_t BUFFER_SIZE = 4096;

// 预定义HTTP请求部分，使用constexpr优化
constexpr const char* REQ_PART1 = "GET /eportal/portal/login?login_method=1";
constexpr const char* REQ_PART2 = "&user_account=,0,"  ACCOUNT ;
constexpr const char* REQ_PART3 = "&user_password=" PASSWORD " HTTP/1.1\r\n";
constexpr const char* REQ_PART4 = "Host: 10.10.244.11:801\r\nConnection: close\r\n";
constexpr const char* REQ_PART5 = "Accept: */*\r\n\r\n";

// 无窗口程序入口点
int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    // 初始化变量
    WSADATA wsaData;
    SOCKET sock = INVALID_SOCKET;
    
    // Winsock初始化
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        return 1;
    }
    
    // 创建Socket
    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        WSACleanup();
        return 1;
    }
    
    // 直接使用IP地址连接
    struct sockaddr_in serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(SERVER_PORT);
    inet_pton(AF_INET, SERVER_IP, &(serverAddr.sin_addr));
    
    // 设置超时 - 连接更快失败
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char*)&TIMEOUT_MS, sizeof(TIMEOUT_MS));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char*)&TIMEOUT_MS, sizeof(TIMEOUT_MS));
    
    // 连接服务器
    if (connect(sock, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) != 0) {
        closesocket(sock);
        WSACleanup();
        return 1;
    }
    
    // 一次性发送HTTP请求以减少系统调用
    constexpr size_t req_len = sizeof(REQ_PART1) + sizeof(REQ_PART2) + sizeof(REQ_PART3) + 
                               sizeof(REQ_PART4) + sizeof(REQ_PART5) - 5; // 减5是因为每个字符串计数包含结尾的\0
    
    static char request[1024]; // 足够大以容纳请求
    
    // 构建完整请求
    strcpy(request, REQ_PART1);
    strcat(request, REQ_PART2);
    strcat(request, REQ_PART3);
    strcat(request, REQ_PART4);
    strcat(request, REQ_PART5);
    
    // 一次发送所有数据
    int sendResult = send(sock, request, strlen(request), 0);
    
    // 清理资源
    closesocket(sock);
    WSACleanup();
    
    // 发送成功就立即退出，不等待响应
    return 0;
}

// 保留原main函数，用于调试时使用
#ifdef _DEBUG
int main(void) {
    return WinMain(GetModuleHandle(NULL), NULL, NULL, SW_HIDE);
}
#endif