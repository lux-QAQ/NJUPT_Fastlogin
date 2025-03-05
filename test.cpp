#include <windows.h>
#include <iostream>
#include <iomanip>
#include <string>

// 高精度性能测试函数
double TestExecutionTime(const std::string& exePath, int iterations, bool showProgress = true) {
    LARGE_INTEGER frequency;
    LARGE_INTEGER start, end;
    QueryPerformanceFrequency(&frequency);
    
    // 开始计时
    QueryPerformanceCounter(&start);
    
    int successCount = 0;
    for (int i = 0; i < iterations; i++) {
        if (showProgress && i % 50 == 0) {
            std::cout << "已完成: " << i << "/" << iterations << "\r";
            std::cout.flush();
        }
        
        STARTUPINFO si = { sizeof(STARTUPINFO) };
        PROCESS_INFORMATION pi;
        
        // 创建进程
        if (CreateProcess(
                NULL, // 不指定应用程序名称
                (LPSTR)exePath.c_str(), // 命令行
                NULL, // 进程安全属性
                NULL, // 线程安全属性 
                FALSE, // 不继承句柄
                CREATE_NO_WINDOW, // 不显示窗口
                NULL, // 使用父进程环境
                NULL, // 使用父进程目录
                &si, // STARTUPINFO
                &pi  // PROCESS_INFORMATION
            )) {
            // 等待进程完成
            WaitForSingleObject(pi.hProcess, INFINITE);
            
            // 获取退出码
            DWORD exitCode;
            GetExitCodeProcess(pi.hProcess, &exitCode);
            
            // 关闭句柄
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);
            
            successCount++;
        } else {
            std::cerr << "无法启动进程: " << exePath << ", 错误码: " << GetLastError() << std::endl;
        }
    }
    
    // 结束计时
    QueryPerformanceCounter(&end);
    
    if (showProgress) {
        std::cout << "成功运行次数: " << successCount << "/" << iterations << "        \n";
    }
    
    // 计算耗时（秒）
    return (end.QuadPart - start.QuadPart) / (double)frequency.QuadPart;
}

int main() {
    SetConsoleOutputCP(CP_UTF8); // 设置控制台为UTF-8编码
    
    const int iterations = 1000;
    std::string currentPath = ".\\"; // 使用当前目录
    std::string exe1 = currentPath + "1.exe";
    std::string exe2 = currentPath + "2.exe";
    
    std::cout << "========== 性能测试开始 ==========" << std::endl;
    std::cout << "每个程序将运行 " << iterations << " 次" << std::endl << std::endl;
    
    // 测试1.exe
    std::cout << "测试 1.exe..." << std::endl;
    double time1 = TestExecutionTime(exe1, iterations);
    
    std::cout << std::endl;
    
    // 测试2.exe
    std::cout << "测试 2.exe..." << std::endl;
    double time2 = TestExecutionTime(exe2, iterations);
    
    // 输出结果
    std::cout << std::endl;
    std::cout << "========== 测试结果 ==========" << std::endl;
    std::cout << std::fixed << std::setprecision(3);
    std::cout << "1.exe 总运行时间: " << time1 << " 秒" << std::endl;
    std::cout << "2.exe 总运行时间: " << time2 << " 秒" << std::endl << std::endl;
    
    // 计算每次平均时间
    double avg1 = time1 / iterations * 1000; // 转换为毫秒
    double avg2 = time2 / iterations * 1000;
    std::cout << "1.exe 平均运行时间: " << avg1 << " 毫秒/次" << std::endl;
    std::cout << "2.exe 平均运行时间: " << avg2 << " 毫秒/次" << std::endl << std::endl;
    
    // 比较性能
    if (time1 < time2) {
        std::cout << "1.exe 比 2.exe 快 " << std::setprecision(2) << (time2/time1) << " 倍" << std::endl;
    } else {
        std::cout << "2.exe 比 1.exe 快 " << std::setprecision(2) << (time1/time2) << " 倍" << std::endl;
    }
    
    std::cout << std::endl << "测试完成，按任意键退出..." << std::endl;
    getchar();
    
    return 0;
}