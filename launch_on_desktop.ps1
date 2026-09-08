# Launch Android Emulator on user's interactive desktop (WinSta0\Default)

$csCode = @"
using System;
using System.Runtime.InteropServices;

public class DesktopLauncher {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO {
        public Int32 cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public Int32 dwX;
        public Int32 dwY;
        public Int32 dwXSize;
        public Int32 dwYSize;
        public Int32 dwXCountChars;
        public Int32 dwYCountChars;
        public Int32 dwFillAttribute;
        public Int32 dwFlags;
        public Int16 wShowWindow;
        public Int16 cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION {
        public IntPtr hProcess;
        public IntPtr hThread;
        public Int32 dwProcessId;
        public Int32 dwThreadId;
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern bool CreateProcess(
        string lpApplicationName,
        string lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation
    );

    public static int LaunchOnInteractiveDesktop(string appPath, string cmdLine) {
        STARTUPINFO si = new STARTUPINFO();
        si.cb = Marshal.SizeOf(si);
        si.lpDesktop = "WinSta0\\Default";
        si.dwFlags = 1;
        si.wShowWindow = 1;

        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();

        bool success = CreateProcess(
            appPath,
            cmdLine,
            IntPtr.Zero,
            IntPtr.Zero,
            false,
            0x00000010,
            IntPtr.Zero,
            null,
            ref si,
            out pi
        );

        if (!success) {
            int err = Marshal.GetLastWin32Error();
            Console.WriteLine("CreateProcess failed with error: " + err);
            return -1;
        }

        Console.WriteLine("CreateProcess success! PID: " + pi.dwProcessId);
        return pi.dwProcessId;
    }
}
"@

Add-Type -TypeDefinition $csCode -Language CSharp

$emulatorExe = "C:\Users\Gisa_M3\AppData\Local\Android\Sdk\emulator\emulator.exe"
$cmdLine = "`"$emulatorExe`" -avd medium_phone -gpu host -no-snapshot-save"

Write-Host "ユーザー対話デスクトップ (WinSta0\Default) 上でエミュレーターを起動します..."
$procId = [DesktopLauncher]::LaunchOnInteractiveDesktop($emulatorExe, $cmdLine)
Write-Host "起動結果 PID: $procId"
