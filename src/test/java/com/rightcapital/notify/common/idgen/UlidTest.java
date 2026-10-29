package com.rightcapital.notify.common.idgen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UlidTest {

    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Test
    void 生成结果为26字符大写CrockfordBase32() {
        for (int i = 0; i < 1000; i++) {
            String ulid = Ulid.next();
            assertThat(ulid).hasSize(26);
            assertThat(ulid).matches("[" + CROCKFORD + "]{26}");
            // 不含易混字符 I/L/O/U
            assertThat(ulid).doesNotContain("I", "L", "O", "U");
        }
    }

    @Test
    void 时间前缀单调递增_同秒内生成的ULID按字典序近似有序() throws Exception {
        String first = Ulid.next();
        Thread.sleep(5); // 跨毫秒
        String second = Ulid.next();
        assertThat(first.compareTo(second)).isLessThan(0);
    }

    @Test
    void 大量生成无重复() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(Ulid.next())).isTrue();
        }
    }

    @Test
    void 负时间戳与超范围时间戳被拒绝() {
        assertThatThrownBy(() -> Ulid.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ulid.of(1L << 48)).isInstanceOf(IllegalArgumentException.class);
    }
}
