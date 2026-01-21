UPDATE app_core.sr_algorithms
SET default_config = jsonb_build_object(
        'requestRetention', 0.9,
        'maximumIntervalDays', 36500,
        'graduatingIntervalDays', 1,
        'easyIntervalDays', 4,
        'minimumIntervalMinutes', 1,
        'initialHalfLifeDays', 1.0,
        'minHalfLifeDays', 0.1,
        'maxHalfLifeDays', 36500,
        'learningRate', 0.02,
        'l2', 0.0,
        'learningStepsMinutes', jsonb_build_array(1, 10),
        'relearningStepsMinutes', jsonb_build_array(10)
                     )
WHERE algorithm_id = 'hlr'
  AND (default_config IS NULL OR default_config = 'null'::jsonb);
