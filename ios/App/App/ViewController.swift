import UIKit
import WebKit
import Capacitor
import CoreBluetooth

class ViewController: CAPBridgeViewController, WKScriptMessageHandler {
    
    private var settingsButton: UIButton?
    private var scanAlert: UIAlertController?
    private var discoveredPrinters: [CBPeripheral] = []
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        // Setup BLE printer callbacks
        setupBLEPrinterCallbacks()
    }
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        
        // Add floating settings button if not already added
        if settingsButton == nil {
            addFloatingSettingsButton()
        }
    }
    
    // Customizing the WKWebView configuration before the bridge starts
    override func capacitorDidLoad() {
        super.capacitorDidLoad()
        
        guard let webView = self.bridge?.webView else { return }
        
        // 1. Inject WebView Share polyfill and create message handler matching Android's bridge
        let userContentController = webView.configuration.userContentController
        userContentController.add(self, name: "AndroidShareBridge")
        
        let jsPolyfill = """
        (function() {
            window.AndroidShareBridge = {
                shareFile: function(base64Data, fileName, title, text) {
                    window.webkit.messageHandlers.AndroidShareBridge.postMessage({
                        base64Data: base64Data,
                        fileName: fileName,
                        title: title,
                        text: text
                    });
                }
            };
            
            navigator.share = function(data) {
                return new Promise(function(resolve, reject) {
                    try {
                        if (data && data.files && data.files.length > 0) {
                            var file = data.files[0];
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64Data = reader.result.split(',')[1];
                                window.AndroidShareBridge.shareFile(
                                    base64Data,
                                    file.name,
                                    data.title || 'Compartilhar',
                                    data.text || ''
                                );
                                resolve();
                            };
                            reader.onerror = function(e) {
                                reject(new Error('Erro ao ler arquivo: ' + e.message));
                            };
                            reader.readAsDataURL(file);
                        } else {
                            reject(new Error('Nenhum arquivo fornecido para compartilhar.'));
                        }
                    } catch(e) {
                        reject(e);
                    }
                });
            };
            navigator.canShare = function(data) {
                return true;
            };
        })();
        """
        
        let userScript = WKUserScript(source: jsPolyfill, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        userContentController.addUserScript(userScript)
        
        // 2. Redirect to company portal if slug is already configured
        if let slug = UserDefaults.standard.string(forKey: "company_slug"),
           !slug.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let urlStr = "https://sisbilhar.top/\(slug.trimmingCharacters(in: .whitespacesAndNewlines))"
            if let redirectUrl = URL(string: urlStr) {
                DispatchQueue.main.async {
                    webView.load(URLRequest(url: redirectUrl))
                }
            }
        }
    }
    
    // MARK: - WKNavigationDelegate Interception
    
    override func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            super.webView(webView, decidePolicyFor: navigationAction, decisionHandler: decisionHandler)
            return
        }
        
        let urlString = url.absoluteString
        
        // Intercept Company Setup Protocol
        if urlString.hasPrefix("setcompany://") {
            let slug = urlString.replacingOccurrences(of: "setcompany://", with: "")
            // Sanitize slug
            let sanitizedSlug = slug.components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_")).inverted).joined()
            
            UserDefaults.standard.set(sanitizedSlug, forKey: "company_slug")
            
            let redirectUrlStr = "https://sisbilhar.top/\(sanitizedSlug)"
            if let redirectUrl = URL(string: redirectUrlStr) {
                webView.load(URLRequest(url: redirectUrl))
            }
            decisionHandler(.cancel)
            return
        }
        
        // Intercept RawBT print intent
        if urlString.hasPrefix("intent:") && urlString.contains("package=ru.a402d.rawbtprinter") {
            handleRawBTPrintIntent(urlString)
            decisionHandler(.cancel)
            return
        }
        
        super.webView(webView, decidePolicyFor: navigationAction, decisionHandler: decisionHandler)
    }
    
    // MARK: - WKScriptMessageHandler (Handles native WhatsApp Share)
    
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "AndroidShareBridge",
              let body = message.body as? [String: Any],
              let base64Data = body["base64Data"] as? String,
              let fileName = body["fileName"] as? String else {
            return
        }
        
        let title = body["title"] as? String ?? "Compartilhar"
        let text = body["text"] as? String ?? ""
        
        shareBase64File(base64Data: base64Data, fileName: fileName, title: title, text: text)
    }
    
    private func shareBase64File(base64Data: String, fileName: String, title: String, text: String) {
        guard let data = Data(base64Encoded: base64Data) else {
            showToast("Erro ao decodificar arquivo do recibo.")
            return
        }
        
        let tempDirectory = FileManager.default.temporaryDirectory
        let fileURL = tempDirectory.appendingPathComponent(fileName)
        
        do {
            try data.write(to: fileURL)
            
            let activityViewController = UIActivityViewController(activityItems: [fileURL, text], applicationActivities: nil)
            
            // For iPads, support popover presentations
            if let popoverController = activityViewController.popoverPresentationController {
                popoverController.sourceView = self.view
                popoverController.sourceRect = CGRect(x: self.view.bounds.midX, y: self.view.bounds.midY, width: 0, height: 0)
                popoverController.permittedArrowDirections = []
            }
            
            present(activityViewController, animated: true, completion: nil)
        } catch {
            showToast("Erro ao salvar arquivo para compartilhamento: \(error.localizedDescription)")
        }
    }
    
    // MARK: - Printing Interception (RawBT Intent)
    
    private func handleRawBTPrintIntent(_ intentUrl: String) {
        // Intent URL format: intent:ENC_TEXT#Intent;scheme=rawbt;package=ru.a402d.rawbtprinter;S.browser_fallback_url=...;end;
        guard let rangeStart = intentUrl.range(of: "intent:"),
              let rangeEnd = intentUrl.range(of: "#Intent;") else {
            showToast("Erro ao decodificar intent de impressão.")
            return
        }
        
        let encodedText = String(intentUrl[rangeStart.upperBound..<rangeEnd.lowerBound])
        
        guard let decodedData = decodePercentEncoding(encodedText) else {
            showToast("Erro ao processar bytes de impressão.")
            return
        }
        
        BLEPrinterManager.shared.print(data: decodedData)
    }
    
    private func decodePercentEncoding(_ string: String) -> Data? {
        var data = Data()
        var tempString = string
        
        // Convert custom RawBT sequences if necessary, otherwise decode percent symbols
        tempString = tempString.replacingOccurrences(of: "%1B%45%01", with: "\u{1B}\u{45}\u{01}")
        tempString = tempString.replacingOccurrences(of: "%1B%45%00", with: "\u{1B}\u{45}\u{00}")
        
        // Fast URL decoding fallback to binary data
        var i = tempString.startIndex
        while i < tempString.endIndex {
            let c = tempString[i]
            if c == "%" {
                let start = tempString.index(i, offsetBy: 1)
                let end = tempString.index(i, offsetBy: 3)
                if end <= tempString.endIndex {
                    let hex = String(tempString[start..<end])
                    if let byte = UInt8(hex, radix: 16) {
                        data.append(byte)
                        i = end
                        continue
                    }
                }
            }
            if let asciiValue = c.asciiValue {
                data.append(asciiValue)
            }
            i = tempString.index(after: i)
        }
        
        return data
    }
    
    // MARK: - Floating Settings Button (FAB)
    
    private func addFloatingSettingsButton() {
        let size: CGFloat = 40
        let btn = UIButton(type: .custom)
        btn.frame = CGRect(x: self.view.bounds.width - size - 15,
                           y: self.view.bounds.height - size - 80,
                           width: size,
                           height: size)
        btn.autoresizingMask = [.flexibleLeftMargin, .flexibleTopMargin]
        btn.layer.cornerRadius = size / 2
        btn.backgroundColor = UIColor.black
        btn.alpha = 0.25
        btn.setTitle("⚙", for: .normal)
        btn.titleLabel?.font = UIFont.systemFont(ofSize: 20)
        btn.setTitleColor(UIColor.white, for: .normal)
        btn.addTarget(self, action: #selector(settingsButtonTapped), for: .touchUpInside)
        
        self.view.addSubview(btn)
        self.settingsButton = btn
    }
    
    @objc private func settingsButtonTapped() {
        let alert = UIAlertController(title: "Configurações", message: nil, preferredStyle: .actionSheet)
        
        alert.addAction(UIAlertAction(title: "Alterar Acesso da Empresa", style: .default, handler: { _ in
            UserDefaults.standard.removeObject(forKey: "company_slug")
            if let webView = self.bridge?.webView {
                // Load local setup screen
                let localUrl = URL(string: "capacitor://localhost")!
                webView.load(URLRequest(url: localUrl))
            }
            self.showToast("Configuração da empresa limpa.")
        }))
        
        alert.addAction(UIAlertAction(title: "Configurar Impressora Bluetooth", style: .default, handler: { _ in
            self.showPrinterScanner()
        }))
        
        alert.addAction(UIAlertAction(title: "Resetar Aplicativo", style: .destructive, handler: { _ in
            UserDefaults.standard.removeObject(forKey: "company_slug")
            UserDefaults.standard.removeObject(forKey: "selected_printer_uuid")
            if let webView = self.bridge?.webView {
                let localUrl = URL(string: "capacitor://localhost")!
                webView.load(URLRequest(url: localUrl))
            }
            self.showToast("Configurações resetadas.")
        }))
        
        alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel, handler: nil))
        
        // For iPads
        if let popoverController = alert.popoverPresentationController, let btn = settingsButton {
            popoverController.sourceView = btn
            popoverController.sourceRect = btn.bounds
        }
        
        present(alert, animated: true, completion: nil)
    }
    
    // MARK: - BLE Printer Scanning and Setup
    
    private func setupBLEPrinterCallbacks() {
        BLEPrinterManager.shared.onStatusChanged = { [weak self] status in
            DispatchQueue.main.async {
                self?.showToast(status)
            }
        }
        
        BLEPrinterManager.shared.onPrintFailure = { [weak self] errorMsg in
            DispatchQueue.main.async {
                if errorMsg == "SELECTION_REQUIRED" {
                    self?.showPrinterScanner()
                } else {
                    self?.showToast(errorMsg)
                }
            }
        }
        
        BLEPrinterManager.shared.onDeviceDiscovered = { [weak self] peripheral in
            DispatchQueue.main.async {
                self?.updateScannerList(peripheral)
            }
        }
    }
    
    private func showPrinterScanner() {
        discoveredPrinters.removeAll()
        BLEPrinterManager.shared.startScanning()
        
        let alert = UIAlertController(title: "Buscar Impressoras", message: "Buscando dispositivos térmicos...", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel, handler: { _ in
            BLEPrinterManager.shared.stopScanning()
        }))
        
        self.scanAlert = alert
        present(alert, animated: true, completion: nil)
    }
    
    private func updateScannerList(_ peripheral: CBPeripheral) {
        guard let alert = scanAlert else { return }
        
        if !discoveredPrinters.contains(where: { $0.identifier == peripheral.identifier }) {
            discoveredPrinters.append(peripheral)
            
            let name = peripheral.name ?? "Dispositivo sem nome"
            alert.addAction(UIAlertAction(title: name, style: .default, handler: { [weak self] _ in
                BLEPrinterManager.shared.stopScanning()
                UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: "selected_printer_uuid")
                BLEPrinterManager.shared.connect(peripheral: peripheral)
                self?.showToast("Impressora \(name) selecionada!")
            }))
        }
    }
    
    // MARK: - Toast helper
    
    private func showToast(_ message: String) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        self.present(alert, animated: true, completion: nil)
        
        // Dismiss after 2 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            alert.dismiss(animated: true, completion: nil)
        }
    }
}
