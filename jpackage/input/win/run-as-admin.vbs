' Require every variable to be declared before use.
Option Explicit

' --- Declare all variables ---
Dim UAC, args, javaPath, updaterPath, planPath, appDir, restartUri

' --- Validate the number of arguments ---
If WScript.Arguments.Count < 5 Then
'     MsgBox "Error: the updater requires 5 arguments but received " & WScript.Arguments.Count & ".", 16, "Updater Script Error"
    WScript.Quit
End If

' --- Assign arguments to descriptive variables ---
javaPath    = WScript.Arguments(0)
updaterPath = WScript.Arguments(1)
planPath    = WScript.Arguments(2)
appDir      = WScript.Arguments(3)
' Read the fifth argument at index 4.
restartUri  = WScript.Arguments(4)


' --- Create the Shell object used for elevation ---
Set UAC = CreateObject("Shell.Application")

' --- Build the argument string passed to java.exe ---
' Chr(34) is the most reliable way to embed double quotes.
args = "-jar " & Chr(34) & updaterPath & Chr(34) & " " & Chr(34) & planPath & Chr(34) & " " & Chr(34) & appDir & Chr(34) & " " & Chr(34) & restartUri & Chr(34)

' --- Execute the command with administrator privileges ---
' The "runas" verb triggers the UAC prompt.
UAC.ShellExecute javaPath, args, "", "runas", 1
