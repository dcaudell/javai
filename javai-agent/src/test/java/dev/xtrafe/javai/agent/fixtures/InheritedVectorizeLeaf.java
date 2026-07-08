package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Carries its own {@code @Vectorize} field ({@code detail}) alongside the inherited one
 * ({@code label}, declared on {@link InheritedVectorizeBase}, whose setter this class never
 * redeclares). Proves the weaver synthesizes an overriding {@code setLabel} here -- calling
 * {@code super.setLabel(...)} -- so mutating the inherited field also marks this object dirty and feeds
 * {@code vector()}.
 */
@JavAIVectorizable
public class InheritedVectorizeLeaf extends InheritedVectorizeBase {

    @Vectorize
    private String detail;

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
