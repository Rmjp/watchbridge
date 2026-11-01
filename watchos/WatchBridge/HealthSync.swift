import Foundation
import HealthKit

final class WatchHealth {
    let store = HKHealthStore()
    let identifiers: [HKQuantityTypeIdentifier] = [.stepCount, .heartRate, .activeEnergyBurned]
    var types: Set<HKObjectType> {
        var result = Set<HKObjectType>(identifiers.compactMap { HKObjectType.quantityType(forIdentifier: $0) })
        result.insert(HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!)
        result.insert(HKObjectType.workoutType())
        return result
    }
    func authorize() async throws {
        guard HKHealthStore.isHealthDataAvailable() else { throw BridgeError.message("Health data unavailable") }
        try await store.requestAuthorization(toShare: [], read: types)
    }
    private func samples(type: HKSampleType, start: Date, end: Date) async throws -> [HKSample] {
        try await withCheckedThrowingContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [.strictStartDate])
            let q = HKSampleQuery(sampleType: type, predicate: predicate, limit: 10000, sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]) { _, result, error in
                if let error = error { continuation.resume(throwing: error) }
                else if (result?.count ?? 0) >= 10000 { continuation.resume(throwing: BridgeError.message("Too many health samples in this window. Use a shorter first backfill.")) }
                else { continuation.resume(returning: result ?? []) }
            }
            store.execute(q)
        }
    }
    func records(start: Date, end: Date) async throws -> [[String: Any]] {
        let format = ISO8601DateFormatter(); format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var records = [[String: Any]]()
        for identifier in identifiers {
            let type = HKObjectType.quantityType(forIdentifier: identifier)!
            for sample in try await samples(type: type, start: start, end: end) {
                guard let s = sample as? HKQuantitySample else { continue }
                let finish = max(s.endDate, s.startDate.addingTimeInterval(0.001))
                var r: [String: Any] = ["id": s.uuid.uuidString.lowercased(), "source": s.sourceRevision.source.bundleIdentifier, "device": s.device?.model ?? "Unknown HealthKit device", "start": format.string(from: s.startDate), "end": format.string(from: finish)]
                switch identifier {
                case .stepCount: r["type"] = "steps"; r["value"] = Int(s.quantity.doubleValue(for: .count()))
                case .heartRate: r["type"] = "heartRate"; r["samples"] = [["time": format.string(from: s.startDate), "bpm": Int(s.quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute())))]]
                default: r["type"] = "activeEnergy"; r["value"] = s.quantity.doubleValue(for: .kilocalorie())
                }
                records.append(r)
            }
        }
        // Preserve each source sleep sample identity. Overnight aggregation is deliberately
        // deferred: grouping incomplete stages can otherwise create overlapping duplicates.
        for sample in try await samples(type: HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!, start: start, end: end) {
            guard let s = sample as? HKCategorySample, s.endDate > s.startDate else { continue }
            let stage: String
            switch s.value {
            case HKCategoryValueSleepAnalysis.awake.rawValue: stage = "awake"
            case HKCategoryValueSleepAnalysis.asleepCore.rawValue: stage = "light"
            case HKCategoryValueSleepAnalysis.asleepDeep.rawValue: stage = "deep"
            case HKCategoryValueSleepAnalysis.asleepREM.rawValue: stage = "rem"
            case HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue: stage = "unknown"
            default: continue // In-bed summaries would overlap the stage samples.
            }
            let a = format.string(from: s.startDate), b = format.string(from: s.endDate)
            records.append(["id": s.uuid.uuidString.lowercased(), "source": s.sourceRevision.source.bundleIdentifier, "device": s.device?.model ?? "Unknown HealthKit device", "type": "sleep", "start": a, "end": b, "stages": [["start": a, "end": b, "stage": stage]]])
        }
        for sample in try await samples(type: HKObjectType.workoutType(), start: start, end: end) {
            guard let w = sample as? HKWorkout, w.endDate > w.startDate else { continue }
            let activity: String
            switch w.workoutActivityType { case .walking: activity = "walking"; case .running: activity = "running"; case .cycling: activity = "cycling"; default: activity = "other" }
            records.append(["id": w.uuid.uuidString.lowercased(), "source": w.sourceRevision.source.bundleIdentifier, "device": w.device?.model ?? "Unknown HealthKit device", "type": "workout", "activity": activity, "start": format.string(from: w.startDate), "end": format.string(from: w.endDate)])
        }
        return records
    }
}
