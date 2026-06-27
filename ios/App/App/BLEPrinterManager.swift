import Foundation
import CoreBluetooth

class BLEPrinterManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    
    static let shared = BLEPrinterManager()
    
    var centralManager: CBCentralManager!
    var discoveredPeripherals: [CBPeripheral] = []
    var activePeripheral: CBPeripheral?
    var writeCharacteristic: CBCharacteristic?
    var writeType: CBCharacteristicWriteType = .withoutResponse
    
    var pendingData = Data()
    
    var onDeviceDiscovered: ((CBPeripheral) -> Void)?
    var onStatusChanged: ((String) -> Void)?
    var onPrintSuccess: (() -> Void)?
    var onPrintFailure: ((String) -> Void)?
    
    private override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    func startScanning() {
        discoveredPeripherals.removeAll()
        guard centralManager.state == .poweredOn else {
            onStatusChanged?("Bluetooth desativado ou sem permissão.")
            return
        }
        onStatusChanged?("Buscando impressoras BLE...")
        centralManager.scanForPeripherals(withServices: nil, options: nil)
    }
    
    func stopScanning() {
        centralManager.stopScan()
    }
    
    func connect(peripheral: CBPeripheral) {
        activePeripheral = peripheral
        activePeripheral?.delegate = self
        onStatusChanged?("Conectando a \(peripheral.name ?? "Dispositivo")...")
        centralManager.connect(peripheral, options: nil)
    }
    
    func disconnect() {
        if let peripheral = activePeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        activePeripheral = nil
        writeCharacteristic = nil
    }
    
    func print(data: Data) {
        self.pendingData = data
        
        // Check if we have a saved printer UUID
        if let savedUUIDString = UserDefaults.standard.string(forKey: "selected_printer_uuid"),
           let uuid = UUID(uuidString: savedUUIDString) {
            let peripherals = centralManager.retrievePeripherals(withIdentifiers: [uuid])
            if let targetPeripheral = peripherals.first {
                connect(peripheral: targetPeripheral)
                return
            }
        }
        
        // If not saved or not found, notify delegate to show scanner popup
        onStatusChanged?("Nenhuma impressora pareada. Selecione uma da lista.")
        onPrintFailure?("SELECTION_REQUIRED")
    }
    
    // MARK: - CBCentralManagerDelegate
    
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            print("BLE is powered on")
        case .poweredOff:
            onStatusChanged?("Bluetooth está desativado")
        case .unauthorized:
            onStatusChanged?("Permissão de Bluetooth negada")
        default:
            onStatusChanged?("Bluetooth indisponível")
        }
    }
    
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        // Filter out unnamed peripherals or duplicates
        guard let name = peripheral.name, !name.isEmpty else { return }
        
        if !discoveredPeripherals.contains(where: { $0.identifier == peripheral.identifier }) {
            discoveredPeripherals.append(peripheral)
            onDeviceDiscovered?(peripheral)
        }
    }
    
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        onStatusChanged?("Conectado! Buscando serviços...")
        peripheral.discoverServices(nil)
    }
    
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        onStatusChanged?("Falha ao conectar: \(error?.localizedDescription ?? "Erro desconhecido")")
        onPrintFailure?("Falha de conexão com a impressora.")
        disconnect()
    }
    
    func centralManager(_ central: CBentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        onStatusChanged?("Desconectado da impressora.")
    }
    
    // MARK: - CBPeripheralDelegate
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            onStatusChanged?("Erro ao buscar serviços: \(error.localizedDescription)")
            onPrintFailure?("Erro ao inicializar impressora.")
            disconnect()
            return
        }
        
        guard let services = peripheral.services, !services.isEmpty else {
            onStatusChanged?("Nenhum serviço BLE encontrado.")
            onPrintFailure?("Impressora incompatível ou sem serviços BLE.")
            disconnect()
            return
        }
        
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            onStatusChanged?("Erro ao buscar características: \(error.localizedDescription)")
            return
        }
        
        guard let characteristics = service.characteristics else { return }
        
        // Find write characteristic
        for characteristic in characteristics {
            if characteristic.properties.contains(.writeWithoutResponse) {
                writeCharacteristic = characteristic
                writeType = .withoutResponse
                break
            } else if characteristic.properties.contains(.write) {
                writeCharacteristic = characteristic
                writeType = .withResponse
            }
        }
        
        if writeCharacteristic != nil {
            onStatusChanged?("Impressora pronta. Imprimindo...")
            sendPendingData()
        }
    }
    
    private func sendPendingData() {
        guard let peripheral = activePeripheral, let characteristic = writeCharacteristic else {
            onPrintFailure?("Erro ao encontrar canal de escrita da impressora.")
            disconnect()
            return
        }
        
        let chunkSize = 20
        var offset = 0
        
        func sendNextChunk() {
            if offset >= pendingData.count {
                onStatusChanged?("Impressão concluída com sucesso!")
                onPrintSuccess?()
                // Wait 1 second before disconnecting to ensure buffer is flushed
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    self.disconnect()
                }
                return
            }
            
            let chunkLength = min(chunkSize, pendingData.count - offset)
            let chunk = pendingData.subdata(in: offset..<(offset + chunkLength))
            
            peripheral.writeValue(chunk, for: characteristic, type: writeType)
            offset += chunkLength
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) {
                sendNextChunk()
            }
        }
        
        sendNextChunk()
    }
}
