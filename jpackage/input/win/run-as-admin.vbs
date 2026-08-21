' Require every variable to be declared before use.
Option Explicit

' --- Declare all variables ---
Dim UAC, args, javaPath, updaterPath, planPath, appDir, restartUri, statusPath, operationId
Dim workingDirectory, mainProcessId, fileSystem, statusFile

' --- Validate the number of arguments ---
If WScript.Arguments.Count < 9 Then
    WScript.Quit
End If

' --- Assign arguments to descriptive variables ---
javaPath    = WScript.Arguments(0)
updaterPath = WScript.Arguments(1)
planPath    = WScript.Arguments(2)
appDir      = WScript.Arguments(3)
' Read the fifth argument at index 4.
restartUri  = WScript.Arguments(4)
statusPath  = WScript.Arguments(5)
operationId = WScript.Arguments(6)
workingDirectory = WScript.Arguments(7)
mainProcessId = WScript.Arguments(8)


' --- Create the Shell object used for elevation ---
Set UAC = CreateObject("Shell.Application")

' --- Build the argument string passed to java.exe ---
' Chr(34) is the most reliable way to embed double quotes.
args = "-jar " & Chr(34) & updaterPath & Chr(34) & " " & Chr(34) & planPath & Chr(34) & " " & Chr(34) & appDir & Chr(34) & " " & Chr(34) & restartUri & Chr(34) & " " & Chr(34) & statusPath & Chr(34) & " " & Chr(34) & operationId & Chr(34) & " " & Chr(34) & workingDirectory & Chr(34) & " " & Chr(34) & mainProcessId & Chr(34)

' --- Execute the command with administrator privileges ---
' The "runas" verb triggers the UAC prompt.
On Error Resume Next
UAC.ShellExecute javaPath, args, "", "runas", 1
If Err.Number <> 0 Then
    Set fileSystem = CreateObject("Scripting.FileSystemObject")
    Set statusFile = fileSystem.CreateTextFile(statusPath, True, False)
    statusFile.Write operationId & "|REJECTED"
    statusFile.Close
End If
On Error GoTo 0
