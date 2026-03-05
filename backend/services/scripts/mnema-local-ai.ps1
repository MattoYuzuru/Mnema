$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
python "$ScriptDir/mnema-local-ai.py" $args
