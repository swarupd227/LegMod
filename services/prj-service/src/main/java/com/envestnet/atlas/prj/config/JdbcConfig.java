package com.envestnet.atlas.prj.config;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

import java.util.List;

/**
 * Round-trips Postgres jsonb (PGobject) ↔ String for fields modeled as
 * String in records. The jsonb columns we own (prj.project.metrics today;
 * arch.run.summary in the arch service) are written by the DB default and
 * read back as opaque text.
 */
@Configuration
public class JdbcConfig extends AbstractJdbcConfiguration {

    @Override
    protected List<?> userConverters() {
        return List.of(new PGobjectToStringConverter());
    }

    @ReadingConverter
    static class PGobjectToStringConverter implements Converter<PGobject, String> {
        @Override
        public String convert(PGobject source) { return source.getValue(); }
    }
}
