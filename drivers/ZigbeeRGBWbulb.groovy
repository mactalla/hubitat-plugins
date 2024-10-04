/* groovylint-disable DuplicateNumberLiteral, DuplicateStringLiteral */
/**
 *  MIT License
 *  Copyright 2024 Andrew Fuller
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the 'Software'), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

import groovy.transform.Field

@Field static final String VERSION = '0.1'
@Field static final int DELAY_MS = 200 // Delay between commands; not yet sure why everyone does this

metadata {
    definition(name: 'Zigbee RGBW with Scenes', namespace: 'mactalla', author: 'Andrew Fuller', singleThreaded: true) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'LevelPreset'
        capability "ColorMode"
        capability 'Color Temperature'

        command 'refresh'

        attribute 'minCtKelvin', 'number'
        attribute 'maxCTKelvin', 'number'
    }

    preferences {
        input name: 'debugEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
        input name: 'traceEnable', type: 'bool', title: 'Enable trace logging', defaultValue: true
    }
}

// Zigbee spec table 2.141
@Field static final Map zdpStatus = [
    '00': 'SUCCESS',
    '80': 'INV_REQUESTTYPE',
    '81': 'DEVICE_NOT_FOUND',
    '82': 'INVALID_EP',
    '83': 'NOT_ACTIVE',
    '84': 'NOT_SUPPORTED',
    '88': 'NO_ENTRY',
    '89': 'NO_DESCRIPTOR',
    '8d': 'NOT_AUTHORIZED',
    '8c': 'TABLE_FULL',
]

@Field static final Map clusters = [
    '0000': 'Basic',
    '0003': 'Identify',
    '0004': 'Groups',
    '0005': 'Scenes',
    '0006': 'On/Off',
    '0008': 'Level Control',
    '0019': 'OTA Upgrade',
    '0300': 'Colour Control',
    '0B05': 'Diagnostics',
    '1000': 'Touchlink',
    'FC01': 'Manufacturer specific (seen in Hue)',
    'FC03': 'Manufacturer specific (seen in Hue)',
    'FC04': 'Manufacturer specific (seen in Hue)',
    'FC7C': 'Manufacturer specific (seen in IKEA)',
]

// Zigbee Cluster Library table 2-3
@Field static final Map generalZclCommand = [
    '01': 'READ_ATTR_RESPONSE',
    '04': 'WRITE_ATTR_RESPONSE',
    '07': 'CONFIGURE_REPORTING_RESPONSE',
    '0A': 'REPORT_ATTRIBUTES',
    '0B': 'DEFAULT_RESPONSE',
]

// Zigbee Cluster Library table 2-11
@Field static final Map zclStatus = [
    '00': 'SUCCESS',
    '01': 'FAILURE',
    '7E': 'NOT_AUTHORIZED',
    '7F': 'RESERVED_FIELD_NOT_ZERO',
    '80': 'MALFORMED_COMMAND',
    '81': 'UNSUP_CLUSTER_COMMAND',
    '82': 'UNSUP_GENERAL_COMMAND',
    '83': 'UNSUP_MANUF_CLUSTER_COMMAND',
    '84': 'UNSUP_MANUF_GENERAL_COMMAND',
    '85': 'INVALID_FIELD',
    '86': 'UNSUPPORTED_ATTRIBUTE',
    '87': 'INVALID_VALUE',
    '88': 'READ_ONLY',
    '89': 'INSUFFICIENT_SPACE',
    '8A': 'DUPLICATE_EXISTS',
    '8B': 'NOT_FOUND',
    '8C': 'UNREPORTABLE_ATTRIBUTE',
    '8D': 'INVALID_DATA_TYPE',
    '8E': 'INVALID_SELECTOR',
    '8F': 'WRITE_ONLY',
    '90': 'INCONSISTENT_STARTUP_STATE',
    '91': 'DEFINED_OUT_OF_BAND',
    '92': 'INCONSISTENT',
    '93': 'ACTION_DENIED',
    '94': 'TIMEOUT',
    '95': 'ABORT',
    '96': 'INVALID_IMAGE',
    '97': 'WAIT_FOR_DATA',
    '98': 'NO_IMAGE_AVAILABLE',
    '99': 'REQUIRE_MORE_IMAGE',
    '9a': 'NOTIFICATION_PENDING',
    'C0': 'HARDWARE_FAILURE',
    'C1': 'SOFTWARE_FAILURE',
    'C2': 'CALIBRATION_ERROR',
    'C3': 'UNSUPPORTED_CLUSTER12',
]

@Field static final Map ColorMode = [
    0x00: 'Hue/Sat',
    0x01: 'X/Y',
    0x02: 'CT',
]

void installed() {
    configure()
}

void updated() {
    configure()
}

List<String> configure() {
    trace 'configure'

    debug "state: ${state}"
    state.clear()
    debug "state: ${state}"

    // Configure reporting for the bulb's level state
    // Assuming default Zigbee library values for simplicity
    debug 'configuring reporting'
    requestSimpleDescriptor()

/*
    List cmds = []

    // min == 0, max == 0, change > 0  --> report immediately when change occurs; no period reports when unchanged

    cmds += zigbee.readAttribute(0x0005, 0x0004, [:], DELAY_MS) // Scenes name support

    cmds += 'delay 1000'
    String groupId = '0000'
    String sceneId = '00'
    // cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x02 {0000 00}" // Remove scene
    // cmds += 'delay 100'
    cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x02 {${groupId} ${sceneId}}" // Remove scene
    cmds += 'delay 100'
    // cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x01 {${groupId} ${sceneId}}" // View scene
    // cmds += 'delay 100'
    String tt = '0000'
    // String name = '0548656C6C6F' // 'Hello'
    String name = '00' // ''
    // String ext1ClusterId = '0003'
    // String ext1Length = '04'
    // X(16), Y(16), Enhanced Hue(16), Sat(8), Loop active(8), Lopo direction(8), loop time(16)
    // String ext1fieldSet = "80A06981"
    // String addScene = "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x00 {${groupId} ${sceneId} ${tt} ${name} ${ext1ClusterId} ${ext1Length} ${ext1fieldSet}}" // Add scene
    // String addScene = "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x00 {${groupId} ${sceneId} ${tt} ${name} 0003 0D BD61 3361 0000 76 00 00 1900 FA00 0800 01 FE }" // Add scene
    // String addScene = "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x00 {${groupId} ${sceneId} ${tt} ${name} 0003 0d FFFF FFFF 0000 76 00 00 1900 FA00 0800 01 FE }" // Add scene
    String addScene = "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x00 {${groupId} ${sceneId} ${tt} ${name} 0003 04 BD61 3361 }" // Add scene
    // String addScene = "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x00 {${groupId} ${sceneId} ${tt} ${name} 0003 0D FFFF FFFF FFFF FF FF FF FFFF FA00 }" // Add scene
    // String addScene = "00 03 0D BD 61 33 61 00 00 76 00 00 19 00 FA 00"
    // String addScene = "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x00 {${groupId} ${sceneId} ${tt} ${name}}" // Add scene

    // 00, 0000, 01, 0000: 00, 0600: 01, 01, 0800: 01, FE, 0003: 0D, BD61, 3361, 0000, 76, 00, 00, 1900, FA00

    debug "Add scene cmd: ${addScene}"
    cmds += addScene
    // cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x04 {${groupId} ${sceneId}}" // Store scene
    cmds += 'delay 100'
    cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x01 {${groupId} ${sceneId}}" // View scene
    // debug "cmds: ${cmds}"
    // cmds += "delay 5000"
    // cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0005 0x05 {000001}" // Recall scene

    cmds += requestSimpleDescriptor()

    // return cmds
*/

    return []
}

void parse(String description) {
    trace 'parse'

    List<Map> events = []

    Map message = zigbee.parseDescriptionAsMap(description)
    switch (message.profileId) {
    case "0000": // ZDP message
            // ZDP frames begin 1 byte for the seq num before the data itself
            message['sequenceNum'] = message.data[0]
            message['data'] = message.data.drop(1)
            handleZdp(message)
            assumeNothingExtra(message)
            break
    case "0104": // ZCL // Fallthrough
    case null: // Assumed ZCL -- (some) attribute reports come through this way
            event = handleZcl(message)
            break
    default:
            debug "Message for unhandled profile ${message.profileId}"
            debug "raw  message: ${description}"
            debug "parsed message: ${message}"
    }

    events.each { event ->
        debug "Informing Hubitat of event: ${event}"
        sendEvent(event)
    }
}

List<String> on() {
    trace 'on'
    // return ["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0006 0x01 {}"] // Basic On
    return ["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0006 0x41 {}"] // On w/ recall global scene
    // return zigbee.on()
}

List<String> off() {
    trace 'off'
    return zigbee.off()
}

List<String> setLevel(level, duration = null) {
    trace "setLevel( ${levelObj}, ${duration})"

    // Note: Not updating Hubitat's level state here; relying on the bulb's reporting
    if (duration == null) {
        return zigbee.setLevel(level)
    }
    Integer tt = duration * 10
    return zigbee.setLevel(level, tt)
}

List<String> presetLevel(level) {
    trace "presetLevel( ${level} )"

    // Rescale from 0-100 to 0-FE (254) and convert to hex (FF is "ignore this attribute"; so max at FE)
    Integer levelScaled = (level * 2.54).toInteger()
    String levelHex = intToHexStr(levelScaled)
    // Same as what zigbee.setLevel gives us, but using "Move to Level" instead of "Move to Level with On/Off"
    List<String> cmds = []
    // This SHOULD do what we want; according to ZCL section 3.10.2.1.2
    // but neither Hue nor Ikea bulbs behave that way
    // cmds += ["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 0x00 {${levelHex} 0000}", "delay 20"]

    // Workaround: use OnLevel attr instead
    // Set the OnLevel attribute so the next power on will turn on at this level
    cmds += zigbee.writeAttribute(0x00008, 0x0011, DataType.UINT8, levelScaled, [:], 20)
    // Don't read back this attribute -- Ikea bulbs send back its value as currentLevel even when
    // turned on at a different level :facepalm:
    // cmds += zigbee.readAttribute(0x0008, [0x0011], [:], 20)

    debug "set level w/o on/off + set attr OnLevel: ${cmds}"
    return cmds
}

List<String> setColorTemperature(value, level = null, duration = null) {
    trace "setColorTemperature( ${value}, ${level}, ${duration} )"
    List<String> cmds = []
    if (level != null) {
        cmds += setLevel(level, duration)
    }

    Integer tt = (duration ?: 0) * 10
    Integer ct = value.toInteger()
    Integer mireds = (1000000/ct).toInteger()

    // cmds += zigbee.setColorTemperature(mireds)
    String miredsDto = zigbee.swapOctets(intToHexStr(mireds, 2))
    String ttDto = zigbee.swapOctets(intToHexStr(tt, 2))
    cmds += zigbee.command(0x0300, 0x0A, [:], 0, "${miredsDto} ${ttDto}")
    debug "set CT command(s): ${cmds}"

    return cmds
}

private void handleZdp(Map message) {
    trace 'Handling Zigbee Device Protocol message'

    switch (message.clusterInt) {
        case 0x8004: handleZdpCluster8004(message); break
        case 0x8022: handleZdpCluster8022(message); break
        case 0x8021: handleZdpCluster8021(message); break
        default:
            debug "Unhandled cluster ID: ${message.clusterId}"
    }
}

private Map handleZcl(Map message) {
    trace 'handling Zigbee Home Automation message'
    if (!message.isClusterSpecific) {
        return handleGeneralZclCommand(message)
    }

    switch (message.clusterInt) {
        case 0x0004:
            switch (message.command) {
                case '02':
                    int capacity = hexStrToUnsignedInt(message.data[0])
                    String capacityText
                    if(capacity == 0) {
                        capacityText = "no more groups"
                    } else if(capacity == 0xfe) {
                        capacityText = "at least one more group"
                    } else if(capacity == 0xff) {
                        capacityText = "unsure whether more groups"
                    } else {
                        capacityText = "${capacity} groups"
                    }
                    int numGroups = hexStrToUnsignedInt(message.data[1])
                    debug "Currently member of ${numGroups} group(s) and ${capacityText} may be added"

                    state['groups'] = state['groups'] ?: [:]
                    if(numGroups > 0) {
                        warn "FIXME: cache list of groups we're a member of"
                    }
                    break
            }
            return [:]

        case 0x0005:
            String status = message.data[0]
            switch (message.command) {
                case '00':
                    warn "FIXME: Add Scene response: ${message}"
                    // String groupId = "${message.data[2]}${message.data[1]}" // Little endian
                    // String sceneId = "${message.data[3]}"
                    break
                case '01':
                    warn "FIXME: View Scene response: ${message}"
                    break
                case '06':
                    warn "FIXME: Get scenes membership response ${message}"
                    String capacity = message.data[1]
                    String groupID = "${message.data[3]}${message.data[2]}" // Little endian
                    state.scenes = state.scenes ?: [:]
                    state.scenes[groupID] = []
                    if(status == "00") {
                        int count = hexStrToUnsignedInt(message.data[4])
                        for(i in 0..count-1) {
                            int sceneId = hexStrToUnsignedInt(message.data[5 + i])
                            state.scenes[groupID] += sceneId
                        }
                    }
                    debug "Scene membership for group ${groupID}: ${status} (${zclStatus[status]}), capacity: ${capacity}, membership: ${state.scenes[groupID]}"
                    break
                default:
                    warn "FIXME: unhandled Scenes reponse ${status} (${zclStatus[status]}): ${message}"
            }
            return [:]
    }

    debug "Unhandled ZCL message: ${message}"
    return [:]
}

private Map handleGeneralZclCommand(Map message) {
    trace 'general command'
    String command = generalZclCommand[message.command]
    if (!command) {
        debug "Unknown general command(${message.command}): ${message}"
    }
    switch (command) {
        case 'READ_ATTR_RESPONSE': // Fallthrough
        case 'REPORT_ATTRIBUTES':
            handleAttribute(message.clusterInt, message.attrInt, hexStrToUnsignedInt(message.encoding).toInteger(), message.value)
            message.additionalAttrs?.each { attr ->
                handleAttribute(message.clusterInt, attr.attrInt, hexStrToUnsignedInt(attr.encoding).toInteger(), attr.value)
            }
            break
        case 'WRITE_ATTR_RESPONSE':
            handleWriteAttributeResponse(message)
            assumeNothingExtra(message)
            break
        case 'CONFIGURE_REPORTING_RESPONSE':
            handleConfigureReportingResponse(message)
            assumeNothingExtra(message)
            break
        case 'DEFAULT_RESPONSE':
            handleDefaultResponse(message)
            assumeNothingExtra(message)
            break
        default:
            debug "Unknown ZCL command: ${message.command} / ${command}"
    }
    return [:]
}

// Bind response
private void handleZdpCluster8021(Map message) {
    trace 'cluster 8021'

    // Zigbee spec Section 2.4.4.3.2
    // Valid values: SUCCESS, NOT_SUPPORTED, INVALID_EP, TABLE_FULL, NOT_AUTHORIZED
    String statusCode = message.data[0]
    String status = zdpStatus[statusCode]
    switch (status) {
        case 'SUCCESS':
            debug 'Bind successful'
            break
        case 'NOT_SUPPORTED':
        case 'INVALID_EP':
        case 'TABLE_FULL':
        case 'NOT_AUTHORIZED':
            warn "Failed to bind. ${status}"
            break
        default:
            warn "Unknown bind failure: ${statusCode} / ${status}"
    }
}

// Simple Descriptor response response
private void handleZdpCluster8004(Map message) {
    trace 'cluster 8004'

    // Zigbee spec Section 2.4.4.2.5
    // Valid values: SUCCESS, INVALID_EP, NOT_ACTIVE, DEVICE_NOT_FOUND, INV_REQUESTTYPE or NO_DESCRIPTOR
    String statusCode = message.data[0]
    String status = zdpStatus[statusCode]
    switch (status) {
        case 'SUCCESS':
            Integer descLength = hexStrToUnsignedInt(message.data[3])
            def descriptor = message.data[4..-1]
            handleSimpleDescriptor(descriptor)

            break
        case 'INVALID_EP':
        case 'NOT_ACTIVE':
        case 'DEVICE_NOT_FOUND':
        case 'INV_REQUESTTYPE':
        case 'NO_DESCRIPTOR':
            warn "Failed to fetch descriptor. ${status}"
            break
        default:
            warn "Unknown descriptor failure: ${statusCode} / ${status}"
    }
}

private void handleSimpleDescriptor(List descriptor) {
    trace 'simple descriptor'
    // Zigbee spec Section 2.3.2.5
    debug "Descriptor: ${descriptor}"

    String endpoint = descriptor[0]
    Integer inputClusterCount = hexStrToUnsignedInt(descriptor[6])
    Set<String> inputClusters = []
    for (i in 0..inputClusterCount - 1) {
        Integer pos = 6 + 1 + i*2
        String cluster = "${descriptor[pos+1]}${descriptor[pos]}"
        inputClusters += cluster
    }
    debug "Input clusters: ${inputClusters}"

    for (cluster in inputClusters) {
        debug "Supported input cluster: ${cluster} (${clusters[cluster]})"
    }

    Integer outputClusterCountIdx = 6 + (inputClusterCount * 2) + 1
    Integer outputClusterCount = hexStrToUnsignedInt(descriptor[outputClusterCountIdx])
    Set<String> outputClusters = []
    for (i in 0..outputClusterCount - 1) {
        Integer pos = outputClusterCountIdx + 1 + i*2
        String cluster = "${descriptor[pos+1]}${descriptor[pos]}"
        outputClusters += cluster
    }
    debug "Output clusters: ${outputClusters}"
    for (cluster in outputClusters) {
        debug "Supported output cluster: ${cluster} (${clusters[cluster]})"
    }

    state['clusters'] = [
        'input': inputClusters,
        'output': outputClusters
    ]

    debug "Endpoint 0x${endpoint} has ${inputClusterCount} input clusters and ${outputClusterCount} output clusters"

    for (cluster in inputClusters) {
        switch (clusters[cluster]) {
            case 'On/Off':
                configureOnOffCluster()
                break
            case 'Level Control':
                configureLevelCluster()
                break
            case 'Colour Control':
                configureColourControlCluster()
                break
            case 'Groups':
                configureGroupCluster()
                break
            case 'Scenes':
                configureScenesCluster()
                break
            default:
                warn "Not handling cluster ${cluster} (${clusters[cluster]})"
        }
    }
}

// Unbind response
private void handleZdpCluster8022(Map message) {
    trace 'cluster 8022'

    // Zigbee spec Section 2.4.4.3.3
    // Valid values: SUCCESS, NOT_SUPPORTED, INVALID_EP, NO_ENTRY, NOT_AUTHORIZED
    String statusCode = message.data[0]
    String status = zdpStatus[statusCode]
    switch (status) {
        case 'SUCCESS':
            debug 'Unbind successful'
            break
        case 'NOT_SUPPORTED':
        case 'INVALID_EP':
        case 'NO_ENTRY':
        case 'NOT_AUTHORIZED':
            warn "Failed to unbind. ${status}"
            break
        default:
            warn "Unknown unbind failure: ${statusCode} / ${status}"
    }
}

private void handleAttribute(Integer cluster, Integer attribute, Integer encoding, String valueStr) {
    Object value = decodeValue(valueStr, encoding)
    switch (cluster) {
        case 0x0004:
            switch (attribute) {
                case 0x0000:
                    state['groups'] = state['groups'] ?: [:]
                    state['groups']['name support'] = (value == 1)
                    break
                // FIXME: fetch group membership, then call fetchScenes()
                default:
                    warn "FIXME: handle attribute 0x${zigbee.convertToHexString(cluster, 4)} : 0x${zigbee.convertToHexString(attribute, 4)}"
            }
        case 0x0005:
            switch (attribute) {
                case 0x0000:
                    // Scene count
                    state['scenes'] = state['scenes'] ?: [:]
                    state['scenes']['count'] = value

                    fetchScenes()
                    break
                case 0x0001:
                    // current scene
                    state['scenes'] = state['scenes'] ?: [:]
                    state['scenes']['current scene'] = value
                    break
                case 0x0002:
                    // current group
                    state['scenes'] = state['scenes'] ?: [:]
                    state['scenes']['current group'] = value
                    break
                case 0x0003:
                    // scene valid
                    state['scenes'] = state['scenes'] ?: [:]
                    state['scenes']['is valid'] = value
                    break
                case 0x0004:
                    // sendEvent([name: 'switch', value: (value == 0x00) ? 'off' : 'on'])
                    state['scenes'] = state['scenes'] ?: [:]
                    state['scenes']['name support'] = (value == 1)
                    break
                default:
                    warn "FIXME: handle attribute 0x${zigbee.convertToHexString(cluster, 4)} : 0x${zigbee.convertToHexString(attribute, 4)}"
            }
            break
        case 0x0006:
            switch (attribute) {
                case 0x0000:
                    sendEvent([name: 'switch', value: (value == 0x00) ? 'off' : 'on'])
                    break
                default:
                    warn "FIXME: handle attribute 0x${zigbee.convertToHexString(cluster, 4)} : 0x${zigbee.convertToHexString(attribute, 4)}"
            }
            break
        case 0x0008:
            switch (attribute) {
                case 0x0000:
                    // Scale 0x00..0xfe (aka 0..254) (0xff is reserved to mean 'invalid') to 0..100
                    Integer percent = Math.round(value / 2.54)
                    sendEvent([name: 'level', value: percent])
                    break
                default:
                    warn "FIXME: handle attribute 0x${zigbee.convertToHexString(cluster, 4)} : 0x${zigbee.convertToHexString(attribute, 4)}"
            }
            break
        case 0x0300:
            switch (attribute) {
                case 0x0000:
                    sendEvent([name: 'currentHue', value: value])
                    break
                case 0x0001:
                    sendEvent([name: 'currentSaturation', value: value])
                    break
                case 0x0003:
                    sendEvent([name: 'currentX', value: value])
                    return
                case 0x0004:
                    sendEvent([name: 'currentY', value: value])
                    return
                case 0x0007:
                    sendEvent([name: 'colorTemperature', value: miredsToKelvin(value)])
                    return
                case 0x0008:
                    sendEvent([name: 'colorMode', value: ColorMode[value]])
                    break
                case 0x400B:
                    state['CT'] = state['CT'] ?: [:]
                    state['CT']['max'] = miredsToKelvin(value)
                    break
                case 0x400C:
                    state['CT'] = state['CT'] ?: [:]
                    state['CT']['min'] = miredsToKelvin(value)
                    break
                default:
                    warn "FIXME: handle attribute 0x${zigbee.convertToHexString(cluster, 4)} : 0x${zigbee.convertToHexString(attribute, 4)}"
            }
            break
        default:
            warn "FIXME: handle attribute 0x${zigbee.convertToHexString(cluster, 4)} : 0x${zigbee.convertToHexString(attribute, 4)}"
    }
}

private Object decodeValue(String valueStr, Integer encoding) {
    Integer value = hexStrToUnsignedInt(valueStr)
    // FIXME: handle enums
    return value
}

private Integer miredsToKelvin(Integer mireds) {
    // Integer mireds = hexStrToUnsignedInt(miredStr)
    return (1000000 / mireds).toInteger()
}

private void handleWriteAttributeResponse(Map message) {
    trace 'write attribute response'

    String statusCode = message.data[0]
    String status = zclStatus[statusCode]
    String attr = "${message.data[2]}${message.data[1]}" // Little endian
    debug "Response for attribute ${message.clusterId}:${attr}: ${statusCode} / ${status}"

}

private void handleConfigureReportingResponse(Map message) {
    trace 'configure reporting response'
    String statusCode = message.data[0]
    String status = zclStatus[statusCode]
    // Byte index 1 is direction -- we only have emitted (0x00) in this driver
    String attribute = "${message.data[3]}${message.data[2]}" // little endian
    switch (status) {
        case 'SUCCESS':
            debug 'Reporting successfully configured'
            // On success we don't seem to be told which attribute was successful
            // We can determine it on failures, though.
            // We'll need to count pending + responses and then deduce all non-failed ones were successful
            break
        default:
            warn "Failed to configure reporting for ${message.clusterId}:${attribute}: ${statusCode} / ${status}.  message: ${message}"
    // There should be extra bytes following the status; data[1] is the direction, data[2] & data[3] are
    // the attrID in little endian ie: attrID = "${data[3]}${data[2]}"
    }
}

private void handleDefaultResponse(Map message) {
    trace 'default response'
    String commandId = message.data[0]
    String statusCode = message.data[1]
    String status = zclStatus[statusCode]
    if (status == 'SUCCESS') {
        // Nothing to see here; move along
        return
    }

    warn "Cluster: 0x${message.clusterId}, command: 0x${commandId}, error: ${status}"
}

void refresh() {
    trace 'refresh'

    configureGroupCluster()
    configureScenesCluster()
}

// Warn if we detect additionalAttributes so we can come back and add support
private void assumeNothingExtra(Map message) {
    if(message.additionalAttrs) {
        warn "FIXME: additional attributes detected! Unprocessed: ${message.additionalAttrs}"
    }
}

private Integer nextTransactionSequenceNo() {
    // FIXME FIXME FIXME
    // This is for our (client) own benefit; we're not using it to match up concurrent requests, so not yet important.
    return 42
}

private String requestSimpleDescriptor() {
    String commandPayload = (zigbee.swapOctets(device.deviceNetworkId) + device.endpointId)
    String destNetworkId = device.deviceNetworkId
    int clusterId = 0x0004

    String hubitatSourceEP = '01'
    String zdoEP = '00'

    String sequenceNo = zigbee.convertToHexString(nextTransactionSequenceNo(), 2)

    cmd = "he raw ${destNetworkId} 0x${hubitatSourceEP} 0x${zdoEP} 0x${clusterId} { ${sequenceNo} ${commandPayload} } { 0000 }"

    debug "ZDO command: $cmd"
    // return cmd
    hubitat.device.HubAction hubAction = new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)


    // FIXME: fetch the binding table (mgmt_bind_req)
}

private void configureOnOffCluster() {
    trace 'configure On/Off cluster'
    int cluster = 0x0006

    int onOffAttr = 0x0000
    cmds = zigbee.configureReporting(cluster, onOffAttr, DataType.BOOLEAN, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, onOffAttr, [:], DELAY_MS) // Fetch the current state

    hubitat.device.HubMultiAction actions = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}

private void configureLevelCluster() {
    trace 'configure Level cluster'
    int cluster = 0x0008

    int levelAttr = 0x0000
    cmds = zigbee.configureReporting(cluster, levelAttr, DataType.UINT8, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, levelAttr, [:], DELAY_MS) // Fetch the current state

    hubitat.device.HubMultiAction actions = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}

private void configureColourControlCluster() {
    trace 'configure Colour Control cluster'
    int cluster = 0x0300
    cmds = []

    int hueAttr = 0x0000
    cmds += zigbee.configureReporting(cluster, hueAttr, DataType.UINT8, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, hueAttr, [:], DELAY_MS) // Fetch the current state
    int satAttr = 0x0001
    cmds += zigbee.configureReporting(cluster, satAttr, DataType.UINT8, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, satAttr, [:], DELAY_MS) // Fetch the current state
    int curXAttr = 0x0003
    cmds += zigbee.configureReporting(cluster, curXAttr, DataType.UINT16, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, curXAttr, [:], DELAY_MS) // Fetch the current state
    int curYAttr = 0x0004
    cmds += zigbee.configureReporting(cluster, curYAttr, DataType.UINT16, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, curYAttr, [:], DELAY_MS) // Fetch the current state
    int ctAttr = 0x0007
    cmds += zigbee.configureReporting(cluster, ctAttr, DataType.UINT16, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, ctAttr, [:], DELAY_MS) // Fetch the current state
    int modeAttr = 0x0008
    cmds += zigbee.configureReporting(cluster, modeAttr, DataType.ENUM8, 0, 0, 1, [:], DELAY_MS)
    cmds += zigbee.readAttribute(cluster, modeAttr, [:], DELAY_MS) // Fetch the current state

    // Static attributes
    int minMiredsAttr = 0x400b
    cmds += zigbee.readAttribute(cluster, minMiredsAttr, [:], DELAY_MS)
    int maxMiredsAttr = 0x400c
    cmds += zigbee.readAttribute(cluster, maxMiredsAttr, [:], DELAY_MS)

    hubitat.device.HubMultiAction actions = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}

private void configureGroupCluster() {
    trace 'configure Scenes cluster'
    int cluster = 0x0004
    cmds = []

    int nameSupport = 0x0000
    cmds += zigbee.readAttribute(cluster, nameSupport, [:], DELAY_MS)
    int getGroupMembership = 0x02
    cmds += zigbee.command(cluster, getGroupMembership, [:], DELAY_MS, "0x00")

    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private void configureScenesCluster() {
    trace 'configure Scenes cluster'
    int cluster = 0x0005
    cmds = []

    int sceneCount = 0x0000
    cmds += zigbee.readAttribute(cluster, sceneCount, [:], DELAY_MS)
    int currentScene = 0x0001
    cmds += zigbee.readAttribute(cluster, currentScene, [:], DELAY_MS)
    int currentGroup = 0x0002
    cmds += zigbee.readAttribute(cluster, currentGroup, [:], DELAY_MS)
    int sceneValid = 0x0003
    cmds += zigbee.readAttribute(cluster, sceneValid, [:], DELAY_MS)
    int nameSupport = 0x0004
    cmds += zigbee.readAttribute(cluster, nameSupport, [:], DELAY_MS)

    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private void fetchScenes() {
    trace 'fetching scenes'

    int cluster = 0x0005
    int getSceneMembership = 0x0006
    cmds = []

    cmds += zigbee.command(cluster, getSceneMembership, [:], DELAY_MS, '0x0000')
    List groups = state.groups?.membership ?: []
    groups.each {group ->
        cmds += zigbee.command(cluster, getSceneMembership, [:], DELAY_MS, "0x${intToHexString(group, 4)}")
    }

    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private void trace(Object... args) {
    if (traceEnable) {
        log.trace args.join(' ')
    }
}

private void debug(Object... args) {
    if (debugEnable) {
        log.debug args.join(' ')
    }
}

private void warn(Object... args) {
    log.warn args.join(' ')
}
