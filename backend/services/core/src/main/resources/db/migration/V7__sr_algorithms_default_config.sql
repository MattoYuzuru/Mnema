UPDATE app_core.sr_algorithms
SET default_config = jsonb_build_object(
        'requestRetention', 0.9,
        'maximumIntervalDays', 36500,
        'weights', jsonb_build_array(
                0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
                0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
                   ),
        'learningStepsMinutes', jsonb_build_array(1, 10),
        'relearningStepsMinutes', jsonb_build_array(10),
        'graduatingIntervalDays', 1,
        'easyIntervalDays', 4,
        'minimumIntervalMinutes', 1
                     )
WHERE algorithm_id = 'fsrs_v6'
  AND (default_config IS NULL OR default_config = 'null'::jsonb);

UPDATE app_core.sr_algorithms
SET default_config = jsonb_build_object(
        'learningStepsMinutes', jsonb_build_array(1, 10),
        'relearningStepsMinutes', jsonb_build_array(10),
        'graduatingIntervalDays', 1,
        'easyIntervalDays', 4,
        'initialEaseFactor', 2.5,
        'minimumEaseFactor', 1.3,
        'easyBonus', 1.3,
        'hardFactor', 1.2,
        'intervalModifier', 1.0,
        'maximumIntervalDays', 36500
                     )
WHERE algorithm_id = 'sm2'
  AND (default_config IS NULL OR default_config = 'null'::jsonb);
