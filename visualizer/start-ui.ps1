# Starts the Streamlit UI. Start the backend first (start-backend.ps1).
$ErrorActionPreference = "Stop"
$Port = if ($env:EXPLORER_PORT) { $env:EXPLORER_PORT } else { "8787" }
if (-not $env:STORAGE_EXPLORER_URL) { $env:STORAGE_EXPLORER_URL = "http://127.0.0.1:$Port" }
$Py = if ($env:PYTHON) { $env:PYTHON } else { "python" }
& $Py -c "import streamlit, requests" 2>$null
if ($LASTEXITCODE -ne 0) { throw "Missing Python packages. Run:  $Py -m pip install -r `"$PSScriptRoot\requirements.txt`"" }
Set-Location $PSScriptRoot
& $Py -m streamlit run app.py
