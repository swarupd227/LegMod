package com.envestnet.atlas.prj.repo;

import com.envestnet.atlas.prj.domain.Forecast;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface ForecastRepository extends CrudRepository<Forecast, UUID> {
}
