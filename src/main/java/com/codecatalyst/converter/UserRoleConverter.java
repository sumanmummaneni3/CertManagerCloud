package com.codecatalyst.converter;

import com.codecatalyst.enums.UserRole;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter for UserRole.
 *
 * Converts between the Java UserRole enum and a VARCHAR string in the database.
 * autoApply = true means this converter is automatically used for every UserRole
 * field across all entities — no annotation needed on the field itself.
 *
 * This approach:
 *   - Uses only standard JPA APIs — no Hibernate internals
 *   - Keeps the entity class clean (no @JdbcType annotation needed)
 *   - Works with the native PostgreSQL user_role ENUM type in the schema
 *   - Is portable across JPA providers
 */
@Converter(autoApply = true)
public class UserRoleConverter implements AttributeConverter<UserRole, String> {

    @Override
    public String convertToDatabaseColumn(UserRole role) {
        if (role == null) {
            return null;
        }
        return role.name();
    }

    @Override
    public UserRole convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown UserRole value from database: '" + value + "'", e);
        }
    }
}