package com.bank.transfer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.bank.transfer.domain")
public class ArchitectureTest {

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OTHERS = noClasses().that()
                                                            .resideInAPackage("..domain..")
                                                            .should()
                                                            .dependOnClassesThat()
                                                            .resideInAnyPackage("..application..", "..adapter..",
                                                              "org.springframework..");

  @ArchTest
  static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER = noClasses().that()
                                                             .resideInAPackage("..application..")
                                                             .should()
                                                             .dependOnClassesThat()
                                                             .resideInAnyPackage("..adapter..").allowEmptyShould(true);

  @ArchTest
  static final ArchRule ADAPTER_IN_DOES_NOT_DEPEND_ON_OUT = noClasses().that()
                                                             .resideInAPackage("..adapter.in..")
                                                             .should()
                                                             .dependOnClassesThat()
                                                             .resideInAnyPackage("..adapter.out..").allowEmptyShould(true);

  @ArchTest
  static final ArchRule ADAPTER_OUT_DOES_NOT_DEPEND_ON_IN = noClasses().that()
                                                             .resideInAPackage("..adapter.out..")
                                                             .should()
                                                             .dependOnClassesThat()
                                                             .resideInAnyPackage("..adapter.in..").allowEmptyShould(true);
}
