/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.operator.scalar.ScalarFunctionImplementation;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.LongArrayBlock;
import io.prestosql.spi.function.InvocationConvention;
import io.prestosql.spi.type.TypeSignature;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.metadata.FunctionKind.SCALAR;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.metadata.Signature.comparableWithVariadicBound;
import static io.prestosql.metadata.TestPolymorphicScalarFunction.TestMethods.VARCHAR_TO_BIGINT_RETURN_VALUE;
import static io.prestosql.metadata.TestPolymorphicScalarFunction.TestMethods.VARCHAR_TO_VARCHAR_RETURN_VALUE;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.BLOCK_POSITION;
import static io.prestosql.spi.function.InvocationConvention.InvocationArgumentConvention.NULL_FLAG;
import static io.prestosql.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.prestosql.spi.function.OperatorType.ADD;
import static io.prestosql.spi.function.OperatorType.IS_DISTINCT_FROM;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.Decimals.MAX_SHORT_PRECISION;
import static io.prestosql.spi.type.TypeSignatureParameter.typeVariable;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestPolymorphicScalarFunction
{
    private static final Metadata METADATA = createTestMetadataManager();
    private static final Signature SIGNATURE = Signature.builder()
            .name("foo")
            .kind(SCALAR)
            .returnType(BIGINT.getTypeSignature())
            .argumentTypes(new TypeSignature("varchar", typeVariable("x")))
            .build();
    private static final int INPUT_VARCHAR_LENGTH = 10;
    private static final TypeSignature INPUT_VARCHAR_TYPE = createVarcharType(INPUT_VARCHAR_LENGTH).getTypeSignature();
    private static final Slice INPUT_SLICE = Slices.allocate(INPUT_VARCHAR_LENGTH);
    private static final BoundVariables BOUND_VARIABLES = new BoundVariables(
            ImmutableMap.of("V", METADATA.getType(INPUT_VARCHAR_TYPE)),
            ImmutableMap.of("x", (long) INPUT_VARCHAR_LENGTH));

    private static final TypeSignature DECIMAL_SIGNATURE = new TypeSignature("decimal", typeVariable("a_precision"), typeVariable("a_scale"));
    private static final BoundVariables LONG_DECIMAL_BOUND_VARIABLES = new BoundVariables(
            ImmutableMap.of(),
            ImmutableMap.of("a_precision", MAX_SHORT_PRECISION + 1L, "a_scale", 2L));
    private static final BoundVariables SHORT_DECIMAL_BOUND_VARIABLES = new BoundVariables(
            ImmutableMap.of(),
            ImmutableMap.of("a_precision", (long) MAX_SHORT_PRECISION, "a_scale", 2L));

    @Test
    public void testSelectsMultipleChoiceWithBlockPosition()
            throws Throwable
    {
        Signature signature = Signature.builder()
                .kind(SCALAR)
                .operatorType(IS_DISTINCT_FROM)
                .argumentTypes(DECIMAL_SIGNATURE, DECIMAL_SIGNATURE)
                .returnType(BOOLEAN.getTypeSignature())
                .build();

        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(signature)
                .argumentDefinitions(
                        new FunctionArgumentDefinition(true),
                        new FunctionArgumentDefinition(true))
                .deterministic(true)
                .choice(choice -> choice
                        .argumentProperties(NULL_FLAG, NULL_FLAG)
                        .implementation(methodsGroup -> methodsGroup
                                .methods("shortShort", "longLong")))
                .choice(choice -> choice
                        .argumentProperties(BLOCK_POSITION, BLOCK_POSITION)
                        .implementation(methodsGroup -> methodsGroup
                                .methodWithExplicitJavaTypes("blockPositionLongLong",
                                        asList(Optional.of(Slice.class), Optional.of(Slice.class)))
                                .methodWithExplicitJavaTypes("blockPositionShortShort",
                                        asList(Optional.of(long.class), Optional.of(long.class)))))
                .build();

        ScalarFunctionImplementation functionImplementation = function.specialize(SHORT_DECIMAL_BOUND_VARIABLES, 2, METADATA);

        assertEquals(functionImplementation.getChoices().size(), 2);
        assertEquals(
                functionImplementation.getChoices().get(0).getInvocationConvention(),
                new InvocationConvention(ImmutableList.of(NULL_FLAG, NULL_FLAG), FAIL_ON_NULL, false, false));
        assertEquals(
                functionImplementation.getChoices().get(1).getInvocationConvention(),
                new InvocationConvention(ImmutableList.of(BLOCK_POSITION, BLOCK_POSITION), FAIL_ON_NULL, false, false));
        Block block1 = new LongArrayBlock(0, Optional.empty(), new long[0]);
        Block block2 = new LongArrayBlock(0, Optional.empty(), new long[0]);
        assertFalse((boolean) functionImplementation.getChoices().get(1).getMethodHandle().invoke(block1, 0, block2, 0));
        functionImplementation = function.specialize(LONG_DECIMAL_BOUND_VARIABLES, 2, METADATA);
        assertTrue((boolean) functionImplementation.getChoices().get(1).getMethodHandle().invoke(block1, 0, block2, 0));
    }

    @Test
    public void testSelectsMethodBasedOnArgumentTypes()
            throws Throwable
    {
        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("bigintToBigintReturnExtraParameter"))
                        .implementation(methodsGroup -> methodsGroup
                                .methods("varcharToBigintReturnExtraParameter")
                                .withExtraParameters(context -> ImmutableList.of(context.getLiteral("x")))))
                .build();

        ScalarFunctionImplementation functionImplementation = function.specialize(BOUND_VARIABLES, 1, METADATA);
        assertEquals(functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE), (long) INPUT_VARCHAR_LENGTH);
    }

    @Test
    public void testSelectsMethodBasedOnReturnType()
            throws Throwable
    {
        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarcharCreateSliceWithExtraParameterLength"))
                        .implementation(methodsGroup -> methodsGroup
                                .methods("varcharToBigintReturnExtraParameter")
                                .withExtraParameters(context -> ImmutableList.of(42))))
                .build();

        ScalarFunctionImplementation functionImplementation = function.specialize(BOUND_VARIABLES, 1, METADATA);

        assertEquals(functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE), VARCHAR_TO_BIGINT_RETURN_VALUE);
    }

    @Test
    public void testSameLiteralInArgumentsAndReturnValue()
            throws Throwable
    {
        Signature signature = Signature.builder()
                .name("foo")
                .kind(SCALAR)
                .returnType(new TypeSignature("varchar", typeVariable("x")))
                .argumentTypes(new TypeSignature("varchar", typeVariable("x")))
                .build();

        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(signature)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarchar")))
                .build();

        ScalarFunctionImplementation functionImplementation = function.specialize(BOUND_VARIABLES, 1, METADATA);
        Slice slice = (Slice) functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE);
        assertEquals(slice, VARCHAR_TO_VARCHAR_RETURN_VALUE);
    }

    @Test
    public void testTypeParameters()
            throws Throwable
    {
        Signature signature = Signature.builder()
                .name("foo")
                .kind(SCALAR)
                .typeVariableConstraints(comparableWithVariadicBound("V", "ROW"))
                .returnType(new TypeSignature("V"))
                .argumentTypes(new TypeSignature("V"))
                .build();

        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(signature)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarchar")))
                .build();

        ScalarFunctionImplementation functionImplementation = function.specialize(BOUND_VARIABLES, 1, METADATA);
        Slice slice = (Slice) functionImplementation.getChoices().get(0).getMethodHandle().invoke(INPUT_SLICE);
        assertEquals(slice, VARCHAR_TO_VARCHAR_RETURN_VALUE);
    }

    @Test
    public void testSetsHiddenToTrueForOperators()
    {
        Signature signature = Signature.builder()
                .operatorType(ADD)
                .kind(SCALAR)
                .returnType(new TypeSignature("varchar", typeVariable("x")))
                .argumentTypes(new TypeSignature("varchar", typeVariable("x")))
                .build();

        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(signature)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToVarchar")))
                .build();

        ScalarFunctionImplementation functionImplementation = function.specialize(BOUND_VARIABLES, 1, METADATA);
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "method foo was not found in class io.prestosql.metadata.TestPolymorphicScalarFunction\\$TestMethods")
    public void testFailIfNotAllMethodsPresent()
    {
        SqlScalarFunction.builder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("bigintToBigintReturnExtraParameter"))
                        .implementation(methodsGroup -> methodsGroup.methods("foo")))
                .build();
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "methods must be selected first")
    public void testFailNoMethodsAreSelectedWhenExtraParametersFunctionIsSet()
    {
        SqlScalarFunction.builder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup
                                .withExtraParameters(context -> ImmutableList.of(42))))
                .build();
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "two matching methods \\(varcharToBigintReturnFirstExtraParameter and varcharToBigintReturnExtraParameter\\) for parameter types \\[varchar\\(10\\)\\]")
    public void testFailIfTwoMethodsWithSameArguments()
    {
        SqlScalarFunction function = SqlScalarFunction.builder(TestMethods.class)
                .signature(SIGNATURE)
                .deterministic(true)
                .choice(choice -> choice
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToBigintReturnFirstExtraParameter"))
                        .implementation(methodsGroup -> methodsGroup.methods("varcharToBigintReturnExtraParameter")))
                .build();

        function.specialize(BOUND_VARIABLES, 1, METADATA);
    }

    public static final class TestMethods
    {
        static final Slice VARCHAR_TO_VARCHAR_RETURN_VALUE = Slices.utf8Slice("hello world");
        static final long VARCHAR_TO_BIGINT_RETURN_VALUE = 42L;

        public static Slice varcharToVarchar(Slice varchar)
        {
            return VARCHAR_TO_VARCHAR_RETURN_VALUE;
        }

        public static long varcharToBigint(Slice varchar)
        {
            return VARCHAR_TO_BIGINT_RETURN_VALUE;
        }

        public static long varcharToBigintReturnExtraParameter(Slice varchar, long extraParameter)
        {
            return extraParameter;
        }

        public static long bigintToBigintReturnExtraParameter(long bigint, int extraParameter)
        {
            return bigint;
        }

        public static long varcharToBigintReturnFirstExtraParameter(Slice varchar, long extraParameter1, int extraParameter2)
        {
            return extraParameter1;
        }

        public static Slice varcharToVarcharCreateSliceWithExtraParameterLength(Slice string, int extraParameter)
        {
            return Slices.allocate(extraParameter);
        }

        public static boolean blockPositionLongLong(Block left, int leftPosition, Block right, int rightPosition)
        {
            return true;
        }

        public static boolean blockPositionShortShort(Block left, int leftPosition, Block right, int rightPosition)
        {
            return false;
        }

        public static boolean shortShort(long left, boolean leftNull, long right, boolean rightNull)
        {
            return false;
        }

        public static boolean longLong(Slice left, boolean leftNull, Slice right, boolean rightNull)
        {
            return false;
        }
    }
}
