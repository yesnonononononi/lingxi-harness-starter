package com.summit.sandbox.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * JNA bindings for Win32 account lookup, DACL management, Job Objects,
 * DPAPI credentials, UAC elevation, and {@code CreateProcessWithLogonW}.
 */
final class Win32SandboxApi {
    private Win32SandboxApi() {
    }

    static final int SE_FILE_OBJECT = 1;
    static final int DACL_SECURITY_INFORMATION = 0x00000004;

    static final int SET_ACCESS = 2;
    static final int REVOKE_ACCESS = 4;
    static final int TRUSTEE_IS_SID = 0;
    static final int TRUSTEE_IS_UNKNOWN = 0;
    static final int NO_MULTIPLE_TRUSTEE = 0;

    static final int OBJECT_INHERIT_ACE = 0x1;
    static final int CONTAINER_INHERIT_ACE = 0x2;
    static final int NO_INHERITANCE = 0x0;
    static final int ACCESS_ALLOWED_ACE_TYPE = 0;
    static final int ACL_SIZE_INFORMATION_CLASS = 2;
    static final int SECURITY_DESCRIPTOR_REVISION = 1;
    static final int SECURITY_DESCRIPTOR_MIN_LENGTH = 40;

    // FILE_GENERIC_READ | FILE_GENERIC_EXECUTE (0x001200A9)
    static final int FILE_READ_EXECUTE_MASK = 0x001200A9;
    // FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE (0x001301BF)
    static final int FILE_MODIFY_MASK = 0x001301BF;

    static final int LOGON_WITH_PROFILE = 0x00000001;
    static final int CREATE_SUSPENDED = 0x00000004;
    static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    static final int CREATE_NO_WINDOW = 0x08000000;

    static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;

    static final int SEE_MASK_NOCLOSEPROCESS = 0x00000040;
    static final int SW_HIDE = 0;

    interface Advapi32Ext extends StdCallLibrary {
        Advapi32Ext INSTANCE = Native.load("advapi32", Advapi32Ext.class, W32APIOptions.UNICODE_OPTIONS);

        boolean LookupAccountNameW(
                String systemName,
                String accountName,
                byte[] sid,
                IntByReference sidSize,
                char[] referencedDomainName,
                IntByReference referencedDomainNameSize,
                IntByReference sidNameUse
        );

        boolean EqualSid(
                Pointer sid1,
                Pointer sid2
        );

        boolean ConvertStringSidToSidW(
                String stringSid,
                PointerByReference sid
        );

        int GetNamedSecurityInfoW(
                String objectName,
                int objectType,
                int securityInfo,
                PointerByReference owner,
                PointerByReference group,
                PointerByReference dacl,
                PointerByReference sacl,
                PointerByReference securityDescriptor
        );

        int SetEntriesInAclW(
                int countOfExplicitEntries,
                ExplicitAccess[] listOfExplicitEntries,
                Pointer oldAcl,
                PointerByReference newAcl
        );

        int SetNamedSecurityInfoW(
                String objectName,
                int objectType,
                int securityInfo,
                Pointer owner,
                Pointer group,
                Pointer dacl,
                Pointer sacl
        );

        boolean InitializeSecurityDescriptor(
                Pointer securityDescriptor,
                int revision
        );

        boolean SetSecurityDescriptorDacl(
                Pointer securityDescriptor,
                boolean daclPresent,
                Pointer dacl,
                boolean daclDefaulted
        );

        boolean SetFileSecurityW(
                String fileName,
                int securityInformation,
                Pointer securityDescriptor
        );

        boolean GetAclInformation(
                Pointer acl,
                AclSizeInformation aclInformation,
                int aclInformationLength,
                int aclInformationClass
        );

        boolean GetAce(
                Pointer acl,
                int aceIndex,
                PointerByReference ace
        );

        boolean CreateProcessWithLogonW(
                String username,
                String domain,
                String password,
                int logonFlags,
                String applicationName,
                char[] commandLine,
                int creationFlags,
                Pointer environment,
                String currentDirectory,
                WinBase.STARTUPINFO startupInfo,
                WinBase.PROCESS_INFORMATION processInformation
        );
    }

    interface Kernel32Ext extends StdCallLibrary {
        Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class, W32APIOptions.UNICODE_OPTIONS);

        int GetLastError();

        boolean CloseHandle(WinNT.HANDLE handle);

        Pointer LocalFree(Pointer mem);

        WinNT.HANDLE CreateJobObjectW(Pointer jobAttributes, String name);

        boolean SetInformationJobObject(
                WinNT.HANDLE job,
                int jobObjectInfoClass,
                Pointer jobObjectInfo,
                int jobObjectInfoLength
        );

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);

        int ResumeThread(WinNT.HANDLE thread);

        boolean CreatePipe(
                WinNT.HANDLEByReference readPipe,
                WinNT.HANDLEByReference writePipe,
                WinBase.SECURITY_ATTRIBUTES pipeAttributes,
                int size
        );

        boolean SetHandleInformation(WinNT.HANDLE object, int mask, int flags);

        int WaitForSingleObject(WinNT.HANDLE handle, int milliseconds);

        boolean GetExitCodeProcess(WinNT.HANDLE process, IntByReference exitCode);

        boolean ReadFile(
                WinNT.HANDLE file,
                byte[] buffer,
                int numberOfBytesToRead,
                IntByReference numberOfBytesRead,
                Pointer overlapped
        );
    }

    interface Shell32Ext extends StdCallLibrary {
        Shell32Ext INSTANCE = Native.load("shell32", Shell32Ext.class, W32APIOptions.UNICODE_OPTIONS);

        boolean ShellExecuteExW(ShellExecuteInfo execInfo);
    }

    @Structure.FieldOrder({"pMultipleTrustee", "MultipleTrusteeOperation", "TrusteeForm", "TrusteeType", "ptstrName"})
    public static class Trustee extends Structure {
        public Pointer pMultipleTrustee;
        public int MultipleTrusteeOperation;
        public int TrusteeForm;
        public int TrusteeType;
        public Pointer ptstrName;

        public Trustee() {
        }
    }

    @Structure.FieldOrder({"grfAccessPermissions", "grfAccessMode", "grfInheritance", "Trustee"})
    public static class ExplicitAccess extends Structure {
        public int grfAccessPermissions;
        public int grfAccessMode;
        public int grfInheritance;
        public Trustee Trustee = new Trustee();

        public ExplicitAccess() {
        }
    }

    @Structure.FieldOrder({"AceCount", "AclBytesInUse", "AclBytesFree"})
    public static class AclSizeInformation extends Structure {
        public int AceCount;
        public int AclBytesInUse;
        public int AclBytesFree;

        public AclSizeInformation() {
        }
    }

    @Structure.FieldOrder({"AceType", "AceFlags", "AceSize"})
    public static class AceHeader extends Structure {
        public byte AceType;
        public byte AceFlags;
        public short AceSize;

        public AceHeader(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"Header", "Mask", "SidStart"})
    public static class AccessAllowedAce extends Structure {
        public AceHeader Header;
        public int Mask;
        public int SidStart;

        public AccessAllowedAce(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({
            "PerProcessUserTimeLimit",
            "PerJobUserTimeLimit",
            "LimitFlags",
            "MinimumWorkingSetSize",
            "MaximumWorkingSetSize",
            "ActiveProcessLimit",
            "Affinity",
            "PriorityClass",
            "SchedulingClass"
    })
    public static class JobObjectBasicLimitInformation extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public BaseTSD.SIZE_T MinimumWorkingSetSize;
        public BaseTSD.SIZE_T MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public BaseTSD.ULONG_PTR Affinity;
        public int PriorityClass;
        public int SchedulingClass;

        public JobObjectBasicLimitInformation() {
        }
    }

    @Structure.FieldOrder({
            "ReadOperationCount",
            "WriteOperationCount",
            "OtherOperationCount",
            "ReadTransferCount",
            "WriteTransferCount",
            "OtherTransferCount"
    })
    public static class IoCounters extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;

        public IoCounters() {
        }
    }

    @Structure.FieldOrder({
            "BasicLimitInformation",
            "IoInfo",
            "ProcessMemoryLimit",
            "JobMemoryLimit",
            "PeakProcessMemoryUsed",
            "PeakJobMemoryUsed"
    })
    public static class JobObjectExtendedLimitInformation extends Structure {
        public JobObjectBasicLimitInformation BasicLimitInformation = new JobObjectBasicLimitInformation();
        public IoCounters IoInfo = new IoCounters();
        public BaseTSD.SIZE_T ProcessMemoryLimit;
        public BaseTSD.SIZE_T JobMemoryLimit;
        public BaseTSD.SIZE_T PeakProcessMemoryUsed;
        public BaseTSD.SIZE_T PeakJobMemoryUsed;

        public JobObjectExtendedLimitInformation() {
        }
    }

    @Structure.FieldOrder({
            "cbSize",
            "fMask",
            "hwnd",
            "lpVerb",
            "lpFile",
            "lpParameters",
            "lpDirectory",
            "nShow",
            "hInstApp",
            "lpIDList",
            "lpClass",
            "hkeyClass",
            "dwHotKey",
            "hIconOrMonitor",
            "hProcess"
    })
    public static class ShellExecuteInfo extends Structure {
        public int cbSize;
        public int fMask;
        public WinDef.HWND hwnd;
        public String lpVerb;
        public String lpFile;
        public String lpParameters;
        public String lpDirectory;
        public int nShow;
        public WinDef.HINSTANCE hInstApp;
        public Pointer lpIDList;
        public String lpClass;
        public WinNT.HANDLE hkeyClass;
        public int dwHotKey;
        public WinNT.HANDLE hIconOrMonitor;
        public WinNT.HANDLE hProcess;

        public ShellExecuteInfo() {
            this.cbSize = size();
        }
    }
}
