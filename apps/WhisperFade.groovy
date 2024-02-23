/**
 * TODO:
 *   - Handle fade/shift segments longer than 108 minutes (must split into multiple commands)
 */

import com.hubitat.hub.domain.Event
import com.hubitat.app.DeviceWrapper
import groovy.transform.Field

@Field static final int NUM_SEGMENTS = 6

definition(
    name: 'Whisper Fade',
    namespace: 'mactalla',
    author: 'Andrew Fuller',
    description: 'Gradually fade lights from one state to another',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: ''
)

preferences {
    page name: 'mainPage'
}

Map mainPage() {
    log.trace 'rendering mainPage'
    return dynamicPage(name: 'mainPage', title: 'Whisper Fade', install: true, uninstall: true) {
        section {
            paragraph 'Halo, a Hubitat!'
        }
        section {
            input name: 'lights', type: 'capability.switchLevel', title: 'Lights', multiple: true
        }

        for (int i = 0; i < NUM_SEGMENTS; ++i) {
            section {
                input name: "ct${i}Time", type: 'time', title: 'At', required: false, width: 1
                input name: "ct$i", type: 'number', title: 'Colour temperature', required: false, width: 3 //, hideWhenEmpty: 'mode1Override'
            }
        }

        for (int i = 0; i < NUM_SEGMENTS; ++i) {
            section {
                input name: "level${i}Time", type: 'time', title: 'At', required: false, width: 1
                input name: "level$i", type: 'number', title: 'Level', required: false, width: 3 //, hideWhenEmpty: 'mode1Override'
            }
        }

        log.debug "Settings: ${settings}"
    }
}

// Called when app first installed
void installed() {
    // for now, just write entry to "Logs" when it happens:
    log.trace 'installed()'
    updated()
}

// Called when user presses "Done" button in app
void updated() {
    log.trace 'updated()'
    unsubscribe()
    unschedule()
    initialize()
}

// Called when app uninstalled
// Most apps would not need to do anything here, but it must exist
void uninstalled() {
    log.trace 'uninstalled()'
}

// Called by cron at the beginning of each CT segment
void beginCTSegment() {
    log.trace 'WhisperFade beginCTSegment called'

    Map segment = getCTSegmentInfo()
    if (segment.prev.ct == segment.next.ct) {
        log.debug 'CT is flat until the next milestone; going back to sleep'
        return
    }

    ArrayList onDevices = lights.findAll { light -> light.currentValue('switch') == 'on' }
    beginShift(onDevices, segment)

    ArrayList offDevices = lights - onDevices
    presetCT(offDevices, segment)
}

// Called periodically during segments where the colour shifts and there are lights turned off
void updateCTPreset() {
    log.trace 'WhisperFade updateCTPreset called'

    Map segment = getCTSegmentInfo()
    if (segment.prev.ct == segment.next.ct) {
        log.debug 'CT is flat until the next milestone; going back to sleep'
        return
    }

    ArrayList offDevices = lights.findAll { light -> light.currentValue('switch') == 'off' }
    presetCT(offDevices, segment)
}

// Called periodically during segments where the level fades and there are lights turned off
void updateLevelPreset() {
    log.trace 'WhisperFade updateLevelPreset called'

    Map segment = getLevelSegmentInfo()
    if (segment.prev.level == segment.next.level) {
        log.debug 'CT is flat until the next milestone; going back to sleep'
        return
    }

    ArrayList offDevices = lights.findAll { light -> light.currentValue('switch') == 'off' }
    presetLevel(offDevices, segment)
}

// Called by cron at the beginning of each Level segment
void beginLevelSegment() {
    log.trace 'WhisperFade beginLevelSegment called'

    Map segment = getLevelSegmentInfo()

    log.debug "prevTarget was ${segment.prev.level} vs upcoming target ${segment.next.level}"
    if (segment.prev.level == segment.next.level) {
        log.debug 'Level is flat until the next milestone; going back to sleep'
        return
    }

    ArrayList onDevices = lights.findAll { light -> light.currentValue('switch') == 'on' }

    int expectedLevel = segment.prev.level
    // Lights that have been adjusted should keep doing what they were told elsewhere
    ArrayList ignoredDevices = onDevices.findAll { light -> light.currentLevel != expectedLevel }
    ignoredDevices.each { light ->
        log.debug "Light ${light} is not at the expected level; leaving as-is"
    }

    ArrayList activeDevices = onDevices - ignoredDevices
    beginFade(activeDevices, segment)

    ArrayList offDevices = lights - onDevices
    presetLevel(offDevices, segment)
}

// Called when a light gets turned On
void lightOn(Event evt) {
    log.trace 'lightOn'

    // If light ramps up over time and we send a setLevel too soon, then it will begin its fade from the wrong starting point (too dim)
    // Watch for the level to reach the expected value before issuing our commands.
    // Initial delay is for the device to have a chance to report its current state else the value read may be from pre-shutoff.
    pauseExecution(1000)

    DeviceWrapper device = evt.getDevice()
    Map segment = getLevelSegmentInfo()
    log.debug "level segment: ${segment}"
    int expectedLevel = segment.current.level
    while (evt.getUnixTime() > (now() - 5000)) {
        // Check that we're still ON; skip the cache as it can be stale
        if (device.currentValue('switch', true) != 'on') {
            log.debug "${evt.getDevice()} was turned OFF before it reach the expected brightness - ignoring"
            return
        }

        // Check if we've reached the expected level, skip the cache and force a read of the freshest value
        int currentLevel = device.currentValue('level', true)
        log.debug "Current vs expected level: ${currentLevel} vs ${expectedLevel}"
        // FIXME: Adjust the fudge factor based on 60s delta
        if (currentLevel > expectedLevel - 5 && currentLevel < expectedLevel + 5) {
            break
        }
        log.debug 'Trying again in 1s'
        pauseExecution(1000)
    }

    beginFade([device], segment)
    beginShift([device], getCTSegmentInfo())
}

// Called when a light gets turned Off
void lightOff(Event evt) {
    log.trace 'lightOff'

    device = evt.getDevice()
    Map levelSegment = getLevelSegmentInfo()
    // FIXME: update ALL Off devices so we don't starve others by pushing the runIn further out each time
    presetLevel([device], levelSegment)
    presetCT([device], getCTSegmentInfo())
}

private void beginShift(ArrayList lights, Map segment) {
    log.trace 'beginShift'
    if (segment.range.ct == 0) {
        // Nothing to do until the next segment
        return
    }

    int transitionTime = segment.next.delta / 1000
    int ct = segment.next.ct
    ArrayList ctLights = lights.findAll { light -> light.hasCommand('colorTemperature') }
    ctLights.each { light ->
        int colorTemperature = light.currentValue('colorTemperature')
        log.debug "Light ${light.displayName} has color temperature: ${colorTemperature}; will change it to ${ct} over ${transitionTime}s"
        light.setColorTemperature(ct, null, transitionTime)
    }
}

private void beginFade(ArrayList devices, Map segment) {
    log.trace 'beginFade'
    if (segment.range.level == 0) {
        // Nothing to do until the next segment
        return
    }

    int expectedLevel = segment.current.level
    ArrayList ignoredDevices = devices.findAll { device ->
        currentLevel = device.currentValue('level')
        return currentLevel < expectedLevel - 5 || currentLevel > expectedLevel + 5
    }
    ignoredDevices.each { device ->
        log.debug "${device} is not at the expected brightness ${expectedLevel} - leaving it as-is"
    }
    activeDevices = devices - ignoredDevices

    int transitionTime = segment.next.delta / 1000
    int level = segment.next.level
    activeDevices.each { device ->
        int currentLevel = device.currentValue('level')
        log.debug "Device ${device.displayName} is at: ${currentLevel}; will change it to ${level} over ${transitionTime}s"
        device.setLevel(level, transitionTime)
    }
}

private void presetLevel(ArrayList devices, Map segment) {
    log.trace 'presetLevel called'
    if (devices.isEmpty()) {
        log.debug 'No devices to preset; disabling updates'
        return
    }

    int level = segment.current.level
    log.debug "pre-setting ${devices} to ${level}%"
    devices*.presetLevel(level)

    if (segment.range.level == 0) {
        // Nothing new for these devices until the next segment
        return
    }

    // number of seconds for 1% delta
    // log.debug "range: ${segment.range.ms}ms / ${segment.range.ct} CT"
    int minPctDelta = (segment.range.ms) / (segment.range.level * 1000)
    log.debug "Time for 1% delta: ${minPctDelta}s"

    // Send out updates every ~1%, but not more frequently than 1x/min
    int sleepTime = Math.max(minPctDelta, 60)
    log.debug "Sleeping for ${sleepTime}"
    runIn(sleepTime, updateLevelPreset)
}

private void presetCT(ArrayList devices, Map segment) {
    log.info 'presetCT called'
    ArrayList ctDevices = devices.findAll { device -> device.hasCommand('setColorTemperature') }
    if (ctDevices.isEmpty() ) {
        return
    }
    if (segment.range.ct == 0) {
        // Nothing to do until the next segment
        return
    }

    int ct = segment.current.ct
    // FIXME: only the fancy Hue driver will let us preset with this command
    //        in the future we need to use the 0:0 scene (I think/hope)
    log.debug "pre-setting ${ctDevices} to ${ct}K"
    ctDevices*.setColorTemperature(ct, null, null)

    // number of seconds for 100 Kelvin delta
    // log.debug "range: ${segment.range.ms}ms / ${segment.range.ct} CT"
    int minKelvinDelta = (100 * segment.range.ms) / (segment.range.ct * 1000)
    log.debug "Time for 100K delta: ${minKelvinDelta}s"

    // Send out updates every ~100K, but not more frequently than 1x/min
    int sleepTime = Math.max(minKelvinDelta, 60)
    log.debug "Sleeping for ${sleepTime}"
    runIn(sleepTime, updateCTPreset)
}

private Map getCTSegmentInfo() {
    Long now = now()

    int prevMilestoneDelta = Integer.MIN_VALUE
    int prevMilestone = -1
    int nextMilestoneDelta = Integer.MAX_VALUE
    int nextMilestone = -1
    for (int i = 0; i < NUM_SEGMENTS; ++i) {
        String rawTime = settings["ct${i}Time"]
        Date time = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", rawTime)

        long delta = time.getTime() - now
        // log.debug "This time is ${delta}ms from now"

        if (delta < 0) {
            if (delta > prevMilestoneDelta) {
                prevMilestoneDelta = delta
                prevMilestone = i
            }

            time = time + 1
            delta = time.getTime() - now

            // FIXME: can delta ever still be negative?  Need to wait a day and see
            if (delta < 0) {
                log.error 'We adjusted an old date/time and it is still in the past'
            }
            if (delta > 0 && delta < nextMilestoneDelta) {
                nextMilestoneDelta = delta
                nextMilestone = i
            }
        } else {
            if (delta < nextMilestoneDelta) {
                nextMilestoneDelta = delta
                nextMilestone = i
            }

            time = time - 1
            delta = time.getTime() - now

            // FIXME: can delta ever still be positive?  Need to wait a day and see
            if (delta > 0) {
                log.error 'We adjusted a future date/time and it is still in the future'
            }
            if (delta < 0 && delta > prevMilestoneDelta) {
                prevMilestoneDelta = delta
                prevMilestone = i
            }
        }
    }

    int prevCT = settings["ct${prevMilestone}"]
    int nextCT = settings["ct${nextMilestone}"]
    int msRange = nextMilestoneDelta - prevMilestoneDelta
    BigDecimal progress = Math.abs(prevMilestoneDelta) / msRange
    log.debug "Progress is ${progress} along the range ${msRange} from ${prevMilestoneDelta} to ${nextMilestoneDelta}"
    int ctRange = nextCT - prevCT
    int currentCT = prevCT + (progress * ctRange)

    return [
        prev: [
            delta: prevMilestoneDelta as int,
            ct: prevCT as int,
        ],
        next: [
            delta: nextMilestoneDelta as int,
            ct: nextCT as int,
        ],
        current: [
            delta: 0,
            ct: currentCT as int,
        ],
        range: [
            ms: msRange as long,
            ct: Math.abs(ctRange) as int,
        ]
    ]
}

private Map getLevelSegmentInfo() {
    Long now = now()

    int prevMilestoneDelta = Integer.MIN_VALUE
    int prevMilestone = -1
    int nextMilestoneDelta = Integer.MAX_VALUE
    int nextMilestone = -1
    for (int i = 0; i < NUM_SEGMENTS; ++i) {
        String rawTime = settings["level${i}Time"]
        Date time = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", rawTime)
        long delta = time.getTime() - now

        if (delta < 0) {
            if (delta > prevMilestoneDelta) {
                prevMilestoneDelta = delta
                prevMilestone = i
            }

            time = time + 1
            delta = time.getTime() - now

            // FIXME: can delta ever still be negative?  Need to wait a day and see
            if (delta < 0) {
                log.error 'We adjusted an old date/time and it is still in the past'
            }
            if (delta > 0 && delta < nextMilestoneDelta) {
                nextMilestoneDelta = delta
                nextMilestone = i
            }
        } else {
            if (delta < nextMilestoneDelta) {
                nextMilestoneDelta = delta
                nextMilestone = i
            }

            time = time - 1
            delta = time.getTime() - now

            // FIXME: can delta ever still be positive?  Need to wait a day and see
            if (delta > 0) {
                log.error 'We adjusted a future date/time and it is still in the future'
            }
            if (delta < 0 && delta > prevMilestoneDelta) {
                prevMilestoneDelta = delta
                prevMilestone = i
            }
        }
    }

    int prevLevel = settings["level${prevMilestone}"]
    int nextLevel = settings["level${nextMilestone}"]
    int msRange = nextMilestoneDelta - prevMilestoneDelta
    BigDecimal progress = Math.abs(prevMilestoneDelta) / msRange
    log.debug "Progress is ${progress} along the range ${msRange} from ${prevMilestoneDelta} to ${nextMilestoneDelta}"
    int levelRange = nextLevel - prevLevel
    int currentLevel = prevLevel + (progress * levelRange)

    return [
        prev: [
            delta: prevMilestoneDelta as int,
            level: prevLevel as int,
        ],
        next: [
            delta: nextMilestoneDelta as int,
            level: nextLevel as int,
        ],
        current: [
            delta: 0,
            level: currentLevel as int,
        ],
        range: [
            ms: msRange as long,
            level: Math.abs(levelRange) as int,
        ]
    ]
}

private void initialize() {
    log.trace 'WhisperFade initialize()'
    subscribe(lights, 'switch.on', 'lightOn')
    subscribe(lights, 'switch.off', 'lightOff')

    // Get next event time
    for (int i = 0; i < NUM_SEGMENTS; ++i) {
        String rawTime = settings["ct${i}Time"]
        Date time = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", rawTime)

        int hour = time[Calendar.HOUR_OF_DAY] // For 24-hour format
        int minute = time[Calendar.MINUTE]

        String cronExpression = "0 ${minute} ${hour} * * ? *"

        schedule(cronExpression, beginCTSegment, [overwrite: false])
    }

    // Schedule expected Level events
    for (int i = 0; i < NUM_SEGMENTS; ++i) {
        String rawTime = settings["level${i}Time"]
        Date time = new Date().parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", rawTime)

        int hour = time[Calendar.HOUR_OF_DAY] // For 24-hour format
        int minute = time[Calendar.MINUTE]

        String cronExpression = "0 ${minute} ${hour} * * ? *"

        schedule(cronExpression, beginLevelSegment, [overwrite: false])
    }
}

private void dumpEvent(Event evt) {
    Map info = [
        descriptionText: evt.descriptionText as String,
        source: evt.source,
        isStateChange: evt.isStateChange,
        name: evt.name,
        value: evt.value,
        unit: evt.unit,
        description: evt.description,
        type: evt.type,
        getData: evt.getData(),
        getJsonData: evt.getJsonData(),
        isPhysical: evt.isPhysical(),
        isDigital: evt.isDigital(),
        getDate: evt.getDate(),
        getUnixTime: evt.getUnixTime(),
        getDisplayName: evt.getDisplayName(),
        getDeviceId: evt.getDeviceId(),
        getDevice: evt.getDevice(),
        getLocation: evt.getLocation(),
        getDoubleValue: evt.getDoubleValue(),
        getFloatValue: evt.getFloatValue(),
        getDateValue: evt.getDateValue(),
        getIntegerValue: evt.getIntegerValue(),
        getLongValue: evt.getLongValue(),
        getNumberValue: evt.getNumberValue(),
        getNumericValue: evt.getNumericValue()
    ]
    log.debug "--> Event: ${info}"
}
